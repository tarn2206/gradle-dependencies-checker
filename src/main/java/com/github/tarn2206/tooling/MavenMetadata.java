package com.github.tarn2206.tooling;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Element;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Parsed metadata only; repository access and version policy live elsewhere.
 */
record MavenMetadata(List<String> versions, String latest, String release) {
    MavenMetadata {
        versions = List.copyOf(versions);
    }

    static MavenMetadata read(InputStream input) throws IOException {
        try {
            var builder = secureFactory().newDocumentBuilder();
            // Do not let parser diagnostics print potentially sensitive XML to stderr.
            builder.setErrorHandler(new DefaultHandler() {
                @Override
                public void error(SAXParseException e) throws SAXException { throw e; }
            });
            var versioning = child(builder.parse(input).getDocumentElement(), "versioning");
            if (versioning == null) return new MavenMetadata(List.of(), null, null);

            var versions = new ArrayList<String>();
            var container = child(versioning, "versions");
            if (container != null) {
                for (var node = container.getFirstChild(); node != null; node = node.getNextSibling()) {
                    if (node instanceof Element element && element.getTagName().equals("version")) {
                        versions.add(text(element));
                    }
                }
            }
            return new MavenMetadata(versions, text(child(versioning, "latest")), text(child(versioning, "release")));
        } catch (ParserConfigurationException | SAXException e) {
            throw new IOException("Cannot parse Maven metadata", e);
        }
    }

    private static DocumentBuilderFactory secureFactory() throws ParserConfigurationException {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory;
    }

    private static Element child(Element parent, String name) {
        for (var node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && element.getTagName().equals(name)) return element;
        }
        return null;
    }

    private static String text(Element element) {
        return element == null ? null : element.getTextContent().trim();
    }
}
