package com.lashouwan.protolens;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lashouwan.protolens.capture.SazImporter;
import com.lashouwan.protolens.protocol.ProtocolCatalog;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** 验证 Fiddler 长度分隔格式；二进制中的换行不能被当作下一条记录。 */
class SazImporterTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private ProtocolCatalog emptyCatalog() throws Exception {
        return new ProtocolCatalog(
                mapper.readTree("{\"schemaVersion\":1,\"messages\":{},\"definitions\":{}}"));
    }

    @Test
    void testArchive_ShouldReadBothDirectionsAndRemoveHandshakeSecrets() throws Exception {
        byte[] frame = HexFormat.of().parseHex("820b0000000100640000000100");
        ByteArrayOutputStream records = new ByteArrayOutputStream();
        for (String direction : new String[] {"Request", "Response"}) {
            records.write(
                    ("\r\n"
                                    + direction
                                    + "-Length: "
                                    + frame.length
                                    + "\r\nID: 7\r\nDoneRead: 2026-11-09T12:00:00+08:00\r\n\r\n")
                            .getBytes(StandardCharsets.US_ASCII));
            records.write(frame);
        }
        byte[] archive = archive(records.toByteArray());
        var capture = new SazImporter(emptyCatalog(), mapper).read(archive);
        assertEquals(2, capture.path("messages").size());
        assertEquals("CS", capture.path("messages").path(0).path("direction").asText());
        assertEquals("SC", capture.path("messages").path(1).path("direction").asText());
        assertEquals("UNMAPPED", capture.path("messages").path(0).path("status").asText());
        assertEquals(
                "https://example.test/websocket1",
                capture.path("connections").path(0).path("endpoint").asText());
        assertFalse(capture.toString().contains("private-token"));
    }

    @Test
    void testTruncatedRecord_ShouldRejectWholeImport() throws Exception {
        byte[] broken =
                "Request-Length: 100\r\nID: 1\r\n\r\nabc".getBytes(StandardCharsets.US_ASCII);
        assertThrows(
                IllegalArgumentException.class,
                () -> new SazImporter(emptyCatalog(), mapper).read(archive(broken)));
    }

    private byte[] archive(byte[] records) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("raw/1_w.txt"));
            zip.write(records);
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("raw/1_c.txt"));
            zip.write(
                    "GET https://example.test/websocket1?token=private-token HTTP/1.1\r\nCookie: private-token\r\n\r\n"
                            .getBytes(StandardCharsets.US_ASCII));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("raw/1_s.txt"));
            zip.write(
                    "HTTP/1.1 101 Switching Protocols\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            zip.closeEntry();
        }
        return output.toByteArray();
    }
}
