package com.yuzhihuihe.apiplatform.gateway.skill;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Minimal YCPS 1.0 archives for the skill contract tests.
 *
 * <p>Every archive built here passes the very same {@link SkillPackageInspector} the production
 * upload path runs, so a rejection asserted by a test is always about the behaviour under test and
 * never about the fixture itself.
 */
final class YcpsTestPackages {

    private static final String PLUGIN_SCHEMA =
            "https://agent-plugins.org/schemas/1.0.0/plugin.schema.json";
    private static final String SCHEMA_BASE = "https://schemas.yuzhihuihe.com/ycps/1.0.0/";
    private static final String RUNTIME_MANIFEST = "com.yuzhihuihe.ycps/manifest.json";
    private static final String PERMISSIONS =
            "\"permissionRequests\":{\"tools\":[],\"network\":[],\"filesystem\":[]},"
                    + "\"hostRequirements\":{}";

    private YcpsTestPackages() {}

    static byte[] skill(String code, String version) {
        return skill(code, version, "Article writer", "# " + code + "\n");
    }

    static byte[] skill(String code, String version, String document) {
        return skill(code, version, "Article writer", document);
    }

    static byte[] skill(String code, String version, String description, String document) {
        return archive(files -> {
            files.put(SkillPackageInspector.PLUGIN, plugin(code, version, "SKILL", description));
            files.put(RUNTIME_MANIFEST, """
                    {"$schema":"%sskill-manifest.schema.json","dependencies":[],%s}
                    """.formatted(SCHEMA_BASE, PERMISSIONS));
            files.put("skills/" + code + "/SKILL.md", skillDocument(code, description, document));
        });
    }

    /** EXPERT packages carry exactly one LEAD agent whose instructions are the Entry Skill. */
    static byte[] expert(String code, String version) {
        return expert(code, version, "main");
    }

    /**
     * A SKILL package that declares one exact dependency: the ingest pipeline has to resolve the
     * locked revision before the version can become READY.
     */
    static byte[] skillWithDependency(String code, String version, String dependencyCode) {
        return archive(files -> {
            files.put(SkillPackageInspector.PLUGIN,
                    plugin(code, version, "SKILL", "Dependency holder"));
            files.put(RUNTIME_MANIFEST, """
                    {"$schema":"%sskill-manifest.schema.json","dependencies":[
                     {"key":"helper","relation":"SKILL_BINDING","required":true,"package":{
                      "code":"%s","kind":"SKILL","version":"1.0.0","revision":1,"sha256":"%s"}}],
                     %s}
                    """.formatted(SCHEMA_BASE, dependencyCode, "a".repeat(64), PERMISSIONS));
            files.put("skills/" + code + "/SKILL.md",
                    skillDocument(code, "Dependency holder", "# " + code + "\n"));
        });
    }

    /**
     * A dependency locked to a target that actually exists. The closure resolves against live
     * published packages, so a caller that wants a resolvable edge must first read the target's
     * real version/revision/sha256 — the fixed placeholder above can never match.
     */
    static byte[] skillWithResolvableDependency(
            String code,
            String version,
            String dependencyCode,
            String dependencyVersion,
            int dependencyRevision,
            String dependencySha256) {
        return archive(files -> {
            files.put(SkillPackageInspector.PLUGIN,
                    plugin(code, version, "SKILL", "Dependency holder"));
            files.put(RUNTIME_MANIFEST, """
                    {"$schema":"%sskill-manifest.schema.json","dependencies":[
                     {"key":"helper","relation":"SKILL_BINDING","required":true,"package":{
                      "code":"%s","kind":"SKILL","version":"%s","revision":%d,"sha256":"%s"}}],
                     %s}
                    """.formatted(SCHEMA_BASE, dependencyCode, dependencyVersion,
                            dependencyRevision, dependencySha256, PERMISSIONS));
            files.put("skills/" + code + "/SKILL.md",
                    skillDocument(code, "Dependency holder", "# " + code + "\n"));
        });
    }

    static byte[] expert(String code, String version, String agentId) {
        return archive(files -> {
            files.put(SkillPackageInspector.PLUGIN,
                    plugin(code, version, "EXPERT", "Single agent expert"));
            files.put(RUNTIME_MANIFEST, """
                    {"$schema":"%sexpert-manifest.schema.json","entryAgent":"%s","agents":[
                     {"id":"%s","role":"LEAD","instructions":{"type":"ENTRY_SKILL"},"bindings":[]}],
                     "dependencies":[],%s}
                    """.formatted(SCHEMA_BASE, agentId, agentId, PERMISSIONS));
            files.put("skills/" + code + "/SKILL.md",
                    skillDocument(code, "Single agent expert", "# " + code + "\n"));
        });
    }

    /** TEAM packages carry one LEAD plus every MEMBER, each with its own embedded agent document. */
    static byte[] team(String code, String version, String leadId, List<String> memberIds) {
        List<String> agents = new ArrayList<>();
        agents.add(leadId);
        agents.addAll(memberIds);
        return archive(files -> {
            files.put(SkillPackageInspector.PLUGIN,
                    plugin(code, version, "TEAM", "Multi agent team"));
            List<String> entries = new ArrayList<>();
            entries.add(embeddedAgent(leadId, "LEAD"));
            for (String memberId : memberIds) {
                entries.add(embeddedAgent(memberId, "MEMBER"));
            }
            files.put(RUNTIME_MANIFEST, """
                    {"$schema":"%steam-manifest.schema.json","entryAgent":"%s","agents":[%s],
                     "dependencies":[],"orchestration":{"mode":"SEQUENTIAL","maxRounds":2,
                     "timeoutSeconds":300,"failurePolicy":"FAIL_FAST"},%s}
                    """.formatted(SCHEMA_BASE, leadId, String.join(",", entries), PERMISSIONS));
            for (String agentId : agents) {
                files.put("com.yuzhihuihe.ycps/agents/" + agentId + ".md", agentDocument(agentId));
            }
            files.put("skills/" + code + "/SKILL.md",
                    skillDocument(code, "Multi agent team", "# " + code + "\n"));
        });
    }

    /** Adds or replaces one entry: rejection fixtures such as an unparsable plugin.json. */
    static byte[] replace(byte[] base, String name, String body) {
        Map<String, String> files = read(base);
        files.put(name, body);
        return zip(files);
    }

    /** Drops one entry: rejection fixtures such as a package without plugin.json. */
    static byte[] without(byte[] base, String name) {
        Map<String, String> files = read(base);
        files.remove(name);
        return zip(files);
    }

    /** The verbatim plugin.json of a package: the admin detail must echo it unchanged. */
    static String pluginJsonOf(byte[] archive) {
        return read(archive).get(SkillPackageInspector.PLUGIN);
    }

    private static String plugin(String code, String version, String kind, String description) {
        return """
                {"$schema":"%s","name":"%s","version":"%s","description":"%s",
                 "author":{"name":"Yuzhi Tests"},"license":"MIT","extensions":{
                 "com.yuzhihuihe.ycps":{"ycpsVersion":"1.0.0","kind":"%s","entrySkill":"%s",
                 "manifest":"./com.yuzhihuihe.ycps/manifest.json","requiredFeatures":[]}}}
                """.formatted(PLUGIN_SCHEMA, code, version, description, kind, code);
    }

    private static String skillDocument(String code, String description, String document) {
        return """
                ---
                name: %s
                description: %s
                ---

                %s""".formatted(code, description, document);
    }

    private static String embeddedAgent(String id, String role) {
        return """
                {"id":"%s","role":"%s","source":{"type":"EMBEDDED",
                 "path":"./com.yuzhihuihe.ycps/agents/%s.md"},"bindings":[]}
                """.formatted(id, role, id);
    }

    private static String agentDocument(String id) {
        return """
                ---
                id: %s
                description: %s runtime agent
                ---

                # %s

                Act as %s inside the packaged team.
                """.formatted(id, id, id, id);
    }

    private static byte[] archive(Consumer<Map<String, String>> filler) {
        Map<String, String> files = new LinkedHashMap<>();
        filler.accept(files);
        return zip(files);
    }

    private static byte[] zip(Map<String, String> files) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
                for (Map.Entry<String, String> file : files.entrySet()) {
                    zip.putNextEntry(new ZipEntry(file.getKey()));
                    zip.write(file.getValue().getBytes(StandardCharsets.UTF_8));
                    zip.closeEntry();
                }
            }
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static Map<String, String> read(byte[] archive) {
        Map<String, String> files = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(
                new ByteArrayInputStream(archive), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    files.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
        return files;
    }
}
