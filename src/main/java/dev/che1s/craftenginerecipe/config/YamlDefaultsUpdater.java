package dev.che1s.craftenginerecipe.config;

import org.bukkit.configuration.InvalidConfigurationException;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.nodes.Tag;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Set;

/** 补齐缺失的 YAML 默认值，同时保留用户值、注释和显式 null。 */
public final class YamlDefaultsUpdater {
    private YamlDefaultsUpdater() {}

    public static int update(Path file, InputStream defaults) throws IOException, InvalidConfigurationException {
        if (defaults == null) throw new IOException("Missing bundled YAML defaults");
        String bundled;
        try (defaults) {
            bundled = new String(defaults.readAllBytes(), StandardCharsets.UTF_8);
        }
        Path target = file.toAbsolutePath().normalize();
        boolean existed = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
        if (existed && !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Not a regular YAML file: " + target);
        }
        String original = existed ? Files.readString(target, StandardCharsets.UTF_8) : "";

        LoaderOptions loading = new LoaderOptions();
        loading.setProcessComments(true);
        Yaml parser = new Yaml(loading);
        MappingNode current = mapping(parser, original);
        MappingNode fallback = mapping(parser, bundled);
        validate(current, Collections.newSetFromMap(new IdentityHashMap<>()));
        validate(fallback, Collections.newSetFromMap(new IdentityHashMap<>()));
        int added = merge(current, fallback, "");
        if (added == 0) return 0;

        DumperOptions dumping = new DumperOptions();
        dumping.setProcessComments(true);
        dumping.setAllowUnicode(true);
        dumping.setWidth(160);
        dumping.setSplitLines(false);
        StringWriter output = new StringWriter();
        try {
            new Yaml(dumping).serialize(current, output);
        } catch (YAMLException e) {
            throw new InvalidConfigurationException(e);
        }
        String updated = output.toString();
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), ".cer-yaml-", ".tmp");
        try {
            Files.writeString(temporary, updated, StandardCharsets.UTF_8);
            // 生成新文档期间若用户已修改文件，则不要覆盖这次修改。
            if (existed ? !Files.readString(target, StandardCharsets.UTF_8).equals(original)
                    : Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("YAML file changed during default update: " + target);
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        return added;
    }

    private static MappingNode mapping(Yaml parser, String text) throws InvalidConfigurationException {
        try {
            Node node = parser.compose(new StringReader(text));
            if (node == null) return new MappingNode(Tag.MAP, new ArrayList<>(), DumperOptions.FlowStyle.BLOCK);
            if (node instanceof MappingNode map) {
                // 对于只有注释的文件，SnakeYAML 会使用不可变的特殊标记空映射。
                if (Tag.COMMENT.equals(map.getTag())) map.setTag(Tag.MAP);
                map.setValue(new ArrayList<>(map.getValue()));
                return map;
            }
            throw new InvalidConfigurationException("YAML document must contain a mapping");
        } catch (YAMLException e) {
            throw new InvalidConfigurationException(e);
        }
    }

    private static void validate(Node node, Set<Node> seen) throws InvalidConfigurationException {
        if (!seen.add(node)) return;
        if (node instanceof MappingNode map) {
            Set<String> keys = new HashSet<>();
            for (NodeTuple tuple : map.getValue()) {
                if (!(tuple.getKeyNode() instanceof ScalarNode key)) {
                    throw new InvalidConfigurationException("YAML mapping keys must be scalars");
                }
                if (!keys.add(key.getValue())) {
                    throw new InvalidConfigurationException("Duplicate YAML key: " + key.getValue());
                }
                validate(tuple.getValueNode(), seen);
            }
        } else if (node instanceof SequenceNode list) {
            for (Node value : list.getValue()) validate(value, seen);
        }
    }

    private static int merge(MappingNode current, MappingNode defaults, String prefix) {
        int added = 0;
        for (NodeTuple fallback : defaults.getValue()) {
            String key = ((ScalarNode) fallback.getKeyNode()).getValue();
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            Node existing = local(current, key);
            if (existing != null) {
                if (existing instanceof MappingNode section && fallback.getValueNode() instanceof MappingNode children) {
                    added += merge(section, children, path);
                }
                continue;
            }
            Node inherited = lookup(current, key, Collections.newSetFromMap(new IdentityHashMap<>()));
            if (inherited != null) {
                // 在本地扩展继承来的配置段，确保其锚点来源保持不变。
                if (inherited instanceof MappingNode section && fallback.getValueNode() instanceof MappingNode children) {
                    MappingNode extended = (MappingNode) copy(section, new IdentityHashMap<>());
                    int childAdditions = merge(extended, children, path);
                    if (childAdditions > 0) {
                        current.getValue().add(new NodeTuple(fallback.getKeyNode(), extended));
                        added += childAdditions;
                    }
                }
                continue;
            }
            Node value = fallback.getValueNode();
            if (value instanceof MappingNode children && !children.getValue().isEmpty()) {
                MappingNode section = new MappingNode(children.getTag(), new ArrayList<>(), children.getFlowStyle());
                int childAdditions = merge(section, children, path);
                if (childAdditions == 0) continue;
                current.getValue().add(new NodeTuple(fallback.getKeyNode(), section));
                added += childAdditions;
            } else {
                current.getValue().add(new NodeTuple(fallback.getKeyNode(), value));
                added++;
            }
        }
        return added;
    }

    private static Node copy(Node source, IdentityHashMap<Node, Node> copies) {
        Node previous = copies.get(source);
        if (previous != null) return previous;
        Node result;
        if (source instanceof MappingNode map) {
            MappingNode target = new MappingNode(map.getTag(), new ArrayList<>(), map.getFlowStyle());
            copies.put(source, target);
            for (NodeTuple entry : map.getValue()) {
                target.getValue().add(new NodeTuple(copy(entry.getKeyNode(), copies), copy(entry.getValueNode(), copies)));
            }
            result = target;
        } else if (source instanceof SequenceNode list) {
            SequenceNode target = new SequenceNode(list.getTag(), new ArrayList<>(), list.getFlowStyle());
            copies.put(source, target);
            for (Node entry : list.getValue()) target.getValue().add(copy(entry, copies));
            result = target;
        } else {
            ScalarNode scalar = (ScalarNode) source;
            result = new ScalarNode(scalar.getTag(), scalar.getValue(), scalar.getStartMark(), scalar.getEndMark(), scalar.getScalarStyle());
            copies.put(source, result);
        }
        result.setBlockComments(source.getBlockComments());
        result.setInLineComments(source.getInLineComments());
        result.setEndComments(source.getEndComments());
        return result;
    }

    private static Node local(MappingNode map, String key) {
        for (NodeTuple tuple : map.getValue()) {
            if (((ScalarNode) tuple.getKeyNode()).getValue().equals(key)) return tuple.getValueNode();
        }
        return null;
    }

    private static Node lookup(MappingNode map, String key, Set<Node> seen) {
        if (!seen.add(map)) return null;
        Node own = local(map, key);
        if (own != null) return own;
        Node inherited = null;
        for (NodeTuple tuple : map.getValue()) {
            if (Tag.MERGE.equals(tuple.getKeyNode().getTag())) {
                inherited = tuple.getValueNode();
                break;
            }
        }
        if (inherited instanceof MappingNode merged) return lookup(merged, key, seen);
        if (inherited instanceof SequenceNode sections) {
            for (Node section : sections.getValue()) {
                if (section instanceof MappingNode merged) {
                    Node value = lookup(merged, key, seen);
                    if (value != null) return value;
                }
            }
        }
        return null;
    }
}
