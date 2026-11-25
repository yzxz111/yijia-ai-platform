package com.yuzhihuihe.apiplatform.gateway.skill;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

/**
 * Validates the actual bytes of skill icons and member avatars. SVG remains supported, but active
 * content, external references and CSS are rejected before the object is made reachable.
 */
final class SkillImageValidator {

    private static final byte[] PNG =
            new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
    private static final Set<String> BLOCKED_ELEMENTS = Set.of(
            "script", "foreignobject", "iframe", "object", "embed", "style",
            "animate", "animatemotion", "animatetransform", "set", "discard");
    private static final Pattern SAFE_FRAGMENT_URL =
            Pattern.compile("(?i)^\\s*url\\(\\s*#[A-Za-z0-9_.:-]+\\s*\\)\\s*$");

    private SkillImageValidator() {}

    static void validate(byte[] content, String mediaType) {
        if (content == null || content.length == 0) {
            throw invalid();
        }
        String type = mediaType == null ? "" : mediaType.toLowerCase(Locale.ROOT);
        switch (type) {
            case "image/png" -> require(startsWith(content, PNG));
            case "image/jpeg" -> require(content.length >= 3
                    && content[0] == (byte) 0xff
                    && content[1] == (byte) 0xd8
                    && content[2] == (byte) 0xff);
            case "image/webp" -> require(content.length >= 12
                    && ascii(content, 0, "RIFF")
                    && ascii(content, 8, "WEBP"));
            case "image/svg+xml" -> validateSvg(content);
            default -> throw invalid();
        }
    }

    private static void validateSvg(byte[] content) {
        try {
            // Reject a UTF-16/UTF-32 declaration and malformed UTF-8 before handing bytes to XML.
            String xml = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(content))
                    .toString();
            String lower = xml.toLowerCase(Locale.ROOT);
            if (lower.contains("<!doctype") || lower.contains("<!entity")
                    || lower.contains("javascript:")) {
                throw invalid();
            }
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            Document document = factory.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(content));
            Element root = document.getDocumentElement();
            String rootName = localName(root);
            String namespace = root == null ? null : root.getNamespaceURI();
            require(root != null
                    && "svg".equals(rootName)
                    && (namespace == null || namespace.isBlank()
                            || "http://www.w3.org/2000/svg".equals(namespace)));
            // Start at the document node so processing instructions before/after the root cannot
            // evade the recursive element validation.
            validateNode(document);
        } catch (Exception error) {
            if (error instanceof IllegalArgumentException invalid) {
                throw invalid;
            }
            throw invalid();
        }
    }

    private static void validateNode(Node node) {
        if (node.getNodeType() == Node.PROCESSING_INSTRUCTION_NODE
                || node.getNodeType() == Node.ENTITY_REFERENCE_NODE) {
            throw invalid();
        }
        if (node instanceof Element element) {
            if (BLOCKED_ELEMENTS.contains(localName(element))) {
                throw invalid();
            }
            NamedNodeMap attributes = element.getAttributes();
            for (int index = 0; index < attributes.getLength(); index += 1) {
                Node attribute = attributes.item(index);
                String name = localName(attribute);
                String value = attribute.getNodeValue() == null
                        ? ""
                        : attribute.getNodeValue().strip();
                String lowerName = name.toLowerCase(Locale.ROOT);
                String lowerValue = value.toLowerCase(Locale.ROOT);
                if (lowerName.startsWith("on") || "style".equals(lowerName)
                        || lowerValue.contains("javascript:")
                        || lowerValue.startsWith("data:")) {
                    throw invalid();
                }
                if (("href".equals(lowerName) || "src".equals(lowerName))
                        && !value.isEmpty() && !value.startsWith("#")) {
                    throw invalid();
                }
                if (lowerValue.contains("url(")
                        && !SAFE_FRAGMENT_URL.matcher(value).matches()) {
                    throw invalid();
                }
            }
        }
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            validateNode(child);
        }
    }

    private static String localName(Node node) {
        if (node == null) {
            return "";
        }
        String local = node.getLocalName();
        String name = local == null ? node.getNodeName() : local;
        int separator = name.indexOf(':');
        return (separator < 0 ? name : name.substring(separator + 1)).toLowerCase(Locale.ROOT);
    }

    private static boolean ascii(byte[] content, int offset, String expected) {
        if (offset < 0 || content.length - offset < expected.length()) {
            return false;
        }
        for (int index = 0; index < expected.length(); index += 1) {
            if (content[offset + index] != (byte) expected.charAt(index)) {
                return false;
            }
        }
        return true;
    }

    private static boolean startsWith(byte[] content, byte[] prefix) {
        if (content.length < prefix.length) {
            return false;
        }
        for (int index = 0; index < prefix.length; index += 1) {
            if (content[index] != prefix[index]) {
                return false;
            }
        }
        return true;
    }

    private static void require(boolean valid) {
        if (!valid) {
            throw invalid();
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("invalid_skill_image");
    }
}
