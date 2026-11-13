package com.nhnacademy.blog.rss.presentation;

import com.nhnacademy.blog.global.web.DateTimes;
import com.nhnacademy.blog.rss.application.RssFeed;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.function.UnaryOperator;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

/**
 * RSS 2.0 XML 쓰기 (T141, SUB-07, contracts/rest-api.md RSS 피드 표).
 * JDK의 StAX(XMLStreamWriter)로 쓴다. 제목·본문의 &lt; &amp; 같은 글자는 StAX가 이스케이프한다.
 * 칸은 표준의 뜻대로 채운다: 요약은 description, 전체 본문은 content:encoded(RSS 1.0 Content 모듈).
 */
final class RssFeedWriter {

    static final String CONTENT_NS = "http://purl.org/rss/1.0/modules/content/";
    static final String ATOM_NS = "http://www.w3.org/2005/Atom";

    /** RFC 822 날짜(RSS 2.0 pubDate). 요일·달 이름이 영어로 고정된 형식이라 서버 언어 설정과 상관없다. */
    private static final DateTimeFormatter RFC_822 = DateTimeFormatter.RFC_1123_DATE_TIME;

    private static final XMLOutputFactory FACTORY = XMLOutputFactory.newFactory();

    private RssFeedWriter() {
    }

    /**
     * @param feed     피드 내용
     * @param urls     전체 주소 만들기(블로그 첫 화면, 글, 피드 자신, 본문 이미지)
     */
    static byte[] write(RssFeed feed, Urls urls) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            XMLStreamWriter xml = FACTORY.createXMLStreamWriter(out, StandardCharsets.UTF_8.name());
            xml.writeStartDocument(StandardCharsets.UTF_8.name(), "1.0");
            xml.writeStartElement("rss");
            xml.writeAttribute("version", "2.0");
            xml.writeNamespace("content", CONTENT_NS);
            xml.writeNamespace("atom", ATOM_NS);
            xml.writeStartElement("channel");

            element(xml, "title", feed.name());
            element(xml, "link", urls.blog());
            // channel/description은 필수 칸이라 소개글이 없으면 블로그 이름을 쓴다
            element(xml, "description", isBlank(feed.description()) ? feed.name() : feed.description());
            element(xml, "language", "ko");
            if (!feed.items().isEmpty()) {
                element(xml, "lastBuildDate", rfc822(feed.items().getFirst().publishedAt()));
            }
            element(xml, "generator", "now-log");
            // 피드가 자기 주소를 밝힌다 (RSS Advisory Board 권고)
            xml.writeEmptyElement("atom", "link", ATOM_NS);
            xml.writeAttribute("href", urls.self());
            xml.writeAttribute("rel", "self");
            xml.writeAttribute("type", RssFeedController.RSS_TYPE);

            for (RssFeed.Item item : feed.items()) {
                writeItem(xml, item, urls);
            }

            xml.writeEndElement();
            xml.writeEndElement();
            xml.writeEndDocument();
            xml.close();
        } catch (XMLStreamException e) {
            throw new IllegalStateException("RSS 피드를 쓰지 못했다", e);
        }
        return out.toByteArray();
    }

    private static void writeItem(XMLStreamWriter xml, RssFeed.Item item, Urls urls) throws XMLStreamException {
        String link = urls.post(item.postId());
        xml.writeStartElement("item");
        element(xml, "title", item.title());
        element(xml, "link", link);
        // 글 번호는 플랫폼 전체에서 하나이고 수정해도 바뀌지 않아 글 주소를 그대로 고유값으로 쓴다
        xml.writeStartElement("guid");
        xml.writeAttribute("isPermaLink", "true");
        xml.writeCharacters(link);
        xml.writeEndElement();
        element(xml, "pubDate", rfc822(item.publishedAt()));
        // 카테고리 하나만. 태그는 넣지 않는다(RSS 2.0에 태그 칸이 없어, 섞으면 읽는 쪽이 다시 나눌 수 없다)
        if (item.category() != null) {
            element(xml, "category", item.category());
        }
        if (!isBlank(item.summary())) {
            element(xml, "description", item.summary());
        }
        xml.writeStartElement("content", "encoded", CONTENT_NS);
        xml.writeCharacters(xmlSafe(urls.absoluteImages().apply(item.contentHtml())));
        xml.writeEndElement();
        xml.writeEndElement();
    }

    private static void element(XMLStreamWriter xml, String name, String text) throws XMLStreamException {
        xml.writeStartElement(name);
        xml.writeCharacters(xmlSafe(text));
        xml.writeEndElement();
    }

    static String rfc822(LocalDateTime dateTime) {
        // DB 시각은 시간대 없는 한국 시간이라 +0900을 붙인다
        return RFC_822.format(dateTime.atZone(DateTimes.KST));
    }

    /**
     * XML 1.0에 넣을 수 없는 제어 문자(탭·줄바꿈 말고 U+0000~U+001F 등)를 뺀다.
     * StAX는 이스케이프만 하고 이런 글자는 그대로 써서, 제목에 섞여 있으면 피드 전체가 깨진 XML이 된다.
     */
    static String xmlSafe(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder safe = new StringBuilder(text.length());
        text.codePoints().filter(RssFeedWriter::allowedInXml).forEach(safe::appendCodePoint);
        return safe.toString();
    }

    private static boolean allowedInXml(int c) {
        return c == 0x9 || c == 0xA || c == 0xD
                || (c >= 0x20 && c <= 0xD7FF)
                || (c >= 0xE000 && c <= 0xFFFD)
                || (c >= 0x10000 && c <= 0x10FFFF);
    }

    private static boolean isBlank(String text) {
        return text == null || text.isBlank();
    }

    /** 피드에 쓰는 전체 주소. 요청의 프로토콜·포트를 따르므로 컨트롤러가 만든다. */
    record Urls(String blog, String self, java.util.function.LongFunction<String> postUrl,
                UnaryOperator<String> absoluteImages) {

        String post(Long postId) {
            return postUrl.apply(postId);
        }

    }

}
