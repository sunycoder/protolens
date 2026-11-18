package com.lashouwan.protolens;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lashouwan.protolens.capture.CaptureMessageQuery;
import com.lashouwan.protolens.capture.MessageQuery;

import org.junit.jupiter.api.Test;

/** 搜索摘要必须显示实际命中的字段，保持分页和完整消息的读取契约。 */
class CaptureSearchPreviewTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void testNestedSearch_ShouldShowMatchingFieldBeyondDefaultPreview() {
        ObjectNode capture = mapper.createObjectNode();
        ObjectNode packet = capture.putArray("messages").addObject();
        packet.put("index", 203).put("protocolName", "GcUpdate").put("protocolId", 12);
        ObjectNode body = packet.putObject("body");
        for (int index = 0; index < 5; index++) {
            body.put("unrelated" + index, index);
        }
        body.putObject("nested").put("a/b", "prefix-".repeat(50) + "WantedValue");
        MessageQuery request =
                new MessageQuery("wantedvalue", "", "", "", false, 0, 9999, 0, 100, "asc", "", "");
        ObjectNode result =
                new CaptureMessageQuery(mapper)
                        .messages(capture, request, mapper.createObjectNode());
        assertEquals(1, result.path("total").asInt());
        String preview = result.path("items").path(0).path("preview").asText();
        assertTrue(preview.contains("/nested/a~1b="));
        assertTrue(preview.contains("WantedValue"));
        assertFalse(result.path("items").path(0).has("body"));
        assertEquals("WantedValue", body.path("nested").path("a/b").asText().substring(350));
    }

    @Test
    void testProtocolSearch_ShouldRetainDefaultPreviewWhenBodyDoesNotMatch() {
        ObjectNode capture = mapper.createObjectNode();
        ObjectNode packet = capture.putArray("messages").addObject();
        packet.put("index", 0).put("protocolName", "GcUpdate");
        packet.putObject("body").put("amount", 0).put("enabled", false);
        MessageQuery request =
                new MessageQuery("gcupdate", "", "", "", false, 0, 9999, 0, 100, "asc", "", "");
        ObjectNode result =
                new CaptureMessageQuery(mapper)
                        .messages(capture, request, mapper.createObjectNode());
        assertEquals(
                "amount=0 · enabled=false", result.path("items").path(0).path("preview").asText());
    }
}
