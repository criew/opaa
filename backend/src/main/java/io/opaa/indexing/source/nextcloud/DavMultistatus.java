package io.opaa.indexing.source.nextcloud;

import io.opaa.sourceaccess.BoundedStreams;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Reads a WebDAV {@code multistatus} answer of {@code PROPFIND} into {@link DavResource}s,
 * streaming and without DTDs or external entities. Only properties of a {@code propstat} with
 * status {@code 200} count; an answer that is no {@code multistatus} is refused.
 */
final class DavMultistatus {

  static final String DAV = "DAV:";
  static final String OWNCLOUD = "http://owncloud.org/ns";
  static final String NEXTCLOUD = "http://nextcloud.org/ns";

  /** The properties every listing asks for. */
  static final String PROPFIND_BODY =
      "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
          + "<d:propfind xmlns:d=\"DAV:\" xmlns:oc=\"http://owncloud.org/ns\""
          + " xmlns:nc=\"http://nextcloud.org/ns\"><d:prop>"
          + "<d:resourcetype/><d:getetag/><d:getcontentlength/><d:getcontenttype/>"
          + "<oc:fileid/><nc:mount-type/>"
          + "</d:prop></d:propfind>";

  /** Asks only for the principal of the signed-in user. */
  static final String PRINCIPAL_BODY =
      "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
          + "<d:propfind xmlns:d=\"DAV:\"><d:prop><d:current-user-principal/></d:prop>"
          + "</d:propfind>";

  private static final XMLInputFactory FACTORY = factory();

  private DavMultistatus() {}

  /** The answer's resources in document order. */
  static List<DavResource> parse(InputStream body)
      throws DavFormatException, BoundedStreams.LimitExceededException {
    try {
      XMLStreamReader reader = FACTORY.createXMLStreamReader(body);
      try {
        return read(reader);
      } finally {
        reader.close();
      }
    } catch (XMLStreamException | RuntimeException e) {
      throw limitOr(e, "unreadable multistatus: ");
    }
  }

  /**
   * The size-bound failure inside {@code e}, rethrown as it is - the reader wraps it and would
   * otherwise report an oversized answer as a malformed one - or a format failure.
   */
  private static DavFormatException limitOr(Exception e, String prefix)
      throws BoundedStreams.LimitExceededException {
    Throwable cause = e;
    while (cause != null) {
      if (cause instanceof BoundedStreams.LimitExceededException limit) {
        throw limit;
      }
      cause =
          cause instanceof XMLStreamException stream && stream.getNestedException() != null
              ? stream.getNestedException()
              : cause.getCause() == cause ? null : cause.getCause();
    }
    return new DavFormatException(prefix + e.getMessage());
  }

  /** The {@code href} of the {@code current-user-principal}, {@code null} when none is named. */
  static String principalHref(InputStream body)
      throws DavFormatException, BoundedStreams.LimitExceededException {
    try {
      XMLStreamReader reader = FACTORY.createXMLStreamReader(body);
      try {
        boolean inPrincipal = false;
        while (reader.hasNext()) {
          int event = reader.next();
          if (event == XMLStreamConstants.START_ELEMENT) {
            if (is(reader, DAV, "current-user-principal")) {
              inPrincipal = true;
            } else if (inPrincipal && is(reader, DAV, "href")) {
              return reader.getElementText().trim();
            }
          } else if (event == XMLStreamConstants.END_ELEMENT
              && is(reader, DAV, "current-user-principal")) {
            inPrincipal = false;
          }
        }
        return null;
      } finally {
        reader.close();
      }
    } catch (XMLStreamException | RuntimeException e) {
      throw limitOr(e, "unreadable principal answer: ");
    }
  }

  private static List<DavResource> read(XMLStreamReader reader)
      throws XMLStreamException, DavFormatException {
    List<DavResource> resources = new ArrayList<>();
    boolean multistatus = false;
    Builder current = null;
    Props props = null;
    while (reader.hasNext()) {
      int event = reader.next();
      if (event == XMLStreamConstants.START_ELEMENT) {
        if (is(reader, DAV, "multistatus")) {
          multistatus = true;
        } else if (is(reader, DAV, "response")) {
          current = new Builder();
        } else if (current != null && is(reader, DAV, "href") && props == null) {
          current.href = reader.getElementText().trim();
        } else if (current != null && is(reader, DAV, "propstat")) {
          props = new Props();
        } else if (props != null) {
          readProperty(reader, props);
        }
      } else if (event == XMLStreamConstants.END_ELEMENT) {
        if (props != null && is(reader, DAV, "propstat")) {
          if (props.ok) {
            current.apply(props);
          }
          props = null;
        } else if (current != null && is(reader, DAV, "response")) {
          if (current.href != null) {
            resources.add(current.build());
          }
          current = null;
        }
      }
    }
    if (!multistatus) {
      throw new DavFormatException("no multistatus");
    }
    return resources;
  }

  private static void readProperty(XMLStreamReader reader, Props props) throws XMLStreamException {
    if (is(reader, DAV, "collection")) {
      props.collection = true;
    } else if (is(reader, DAV, "getetag")) {
      props.etag = unquote(reader.getElementText().trim());
    } else if (is(reader, DAV, "getcontentlength")) {
      props.size = parseSize(reader.getElementText().trim());
    } else if (is(reader, DAV, "getcontenttype")) {
      props.contentType = blankToNull(reader.getElementText());
    } else if (is(reader, OWNCLOUD, "fileid")) {
      props.fileId = blankToNull(reader.getElementText());
    } else if (is(reader, NEXTCLOUD, "mount-type")) {
      props.mountType = blankToNull(reader.getElementText());
    } else if (is(reader, DAV, "status")) {
      String status = reader.getElementText().trim();
      props.ok = status.contains(" 200 ");
    }
  }

  private static boolean is(XMLStreamReader reader, String namespace, String localName) {
    return namespace.equals(reader.getNamespaceURI()) && localName.equals(reader.getLocalName());
  }

  private static String unquote(String etag) {
    String value = etag.startsWith("W/") ? etag.substring(2) : etag;
    if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
      value = value.substring(1, value.length() - 1);
    }
    return value.isEmpty() ? null : value;
  }

  private static long parseSize(String text) {
    try {
      return Long.parseLong(text);
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  private static String blankToNull(String text) {
    return text == null || text.isBlank() ? null : text.trim();
  }

  private static XMLInputFactory factory() {
    XMLInputFactory factory = XMLInputFactory.newFactory();
    factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
    factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
    factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    return factory;
  }

  /** The properties of one {@code propstat}. */
  private static final class Props {
    boolean ok;
    boolean collection;
    String etag;
    long size = -1;
    String contentType;
    String fileId;
    String mountType;
  }

  private static final class Builder {
    String href;
    boolean collection;
    String etag;
    long size = -1;
    String contentType;
    String fileId;
    String mountType;

    void apply(Props props) {
      collection |= props.collection;
      etag = props.etag != null ? props.etag : etag;
      size = props.size >= 0 ? props.size : size;
      contentType = props.contentType != null ? props.contentType : contentType;
      fileId = props.fileId != null ? props.fileId : fileId;
      mountType = props.mountType != null ? props.mountType : mountType;
    }

    DavResource build() {
      String path = DavPaths.decode(DavPaths.pathOf(href));
      if (path.length() > 1 && path.endsWith("/")) {
        path = path.substring(0, path.length() - 1);
      }
      return new DavResource(href, path, collection, etag, size, contentType, fileId, mountType);
    }
  }

  /** The server answered something that is no readable WebDAV answer. */
  static final class DavFormatException extends Exception {
    DavFormatException(String message) {
      super(message);
    }
  }
}
