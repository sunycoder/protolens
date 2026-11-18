"""采集 addon 的聚焦测试：真实字节传递、异步队列去重与容量边界，不启动代理。"""
import importlib.util
import pathlib
import types
import unittest

spec = importlib.util.spec_from_file_location("collector", pathlib.Path(__file__).with_name("mitmproxy_collector.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class CollectorTests(unittest.TestCase):
    def collector(self, hosts=None, paths=None):
        return module.AnalyzerCollector(hosts=hosts or ["example.test"], path_prefixes=paths or ["/websocket"])

    def flow(self, path="/websocket1?sign=private", host="example.test"):
        return types.SimpleNamespace(id="9ec8317b-7973-4ca1-975d-b05f179207f7",
            request=types.SimpleNamespace(host=host, path=path, pretty_url="https://"+host+path),
            response=types.SimpleNamespace(headers={"sec-websocket-extensions": "permessage-deflate"}),
            websocket=types.SimpleNamespace(messages=[]))

    def test_packet_copies_actual_bytes_without_mutation(self):
        collector = self.collector()
        flow = self.flow()
        collector.websocket_start(flow)
        payload = bytes.fromhex("00020051093c0000000715f20f12150800")
        message = types.SimpleNamespace(injected=False, dropped=False, type=2, from_client=True, timestamp=1794198830.0, content=payload)
        flow.websocket.messages.append(message)
        collector.websocket_message(flow)
        events = [item[0] for item in collector.queue]
        self.assertNotIn("private", events[0]["endpoint"])
        self.assertEqual("packet", events[1]["type"])
        self.assertEqual(payload, module.base64.b64decode(events[1]["packetBase64"]))
        self.assertIs(message.content, payload)

    def test_other_connections_and_injected_messages_are_not_saved(self):
        collector = self.collector()
        collector.websocket_start(self.flow("/chat"))
        self.assertFalse(collector.queue)
        flow = self.flow()
        collector.websocket_start(flow)
        flow.websocket.messages.append(types.SimpleNamespace(injected=True, dropped=False))
        collector.websocket_message(flow)
        self.assertEqual(1, len(collector.queue))

    def test_custom_hosts_and_paths_apply_to_handshake_and_message_capture(self):
        collector = self.collector(hosts=["new.example.com"], paths=["/custom-game/"])
        for flow in [self.flow("/websocket1", "new.example.com"), self.flow("/custom-game/1", "evilnew.example.com"),
                     self.flow("/custom-game/1", "new.example.com.evil.test"), self.flow("/Custom-game/1", "new.example.com")]:
            collector.websocket_start(flow)
        self.assertFalse(collector.queue)
        collector.websocket_start(self.flow("/custom-game/1?token=private", "wss.new.example.com"))
        self.assertEqual(1, len(collector.queue))
        self.assertNotIn("private", collector.queue[0][0]["endpoint"])

    def test_lost_reply_retries_identical_batch_before_dequeue(self):
        collector = self.collector()
        collector.token = "local-test"
        collector.enqueue({"type": "heartbeat"})
        attempts = []
        def request(method, path, value):
            attempts.append(value)
            if len(attempts) == 1:
                raise TimeoutError("reply lost")
            return {"source": collector.source, "acknowledged": 1}
        collector.call_api = request
        with self.assertRaises(TimeoutError): collector.send_once()
        self.assertEqual(1, len(collector.queue))
        self.assertTrue(collector.send_once())
        self.assertEqual(attempts[0], attempts[1])
        self.assertEqual(0, collector.queued_bytes)

    def test_capacity_stops_without_silent_sequence_gap(self):
        collector = self.collector()
        for _ in range(4096): self.assertTrue(collector.enqueue({"type": "heartbeat"}))
        self.assertFalse(collector.enqueue({"type": "heartbeat"}))
        self.assertTrue(collector.broken)
        self.assertEqual(4096, collector.sequence)

    def test_stop_pauses_capture_and_next_generation_requires_new_handshake(self):
        collector = self.collector()
        collector.websocket_start(self.flow())
        collector.call_api = lambda *_: {"enabled": False, "generation": "first"}
        collector.sync_status()
        self.assertFalse(collector.queue)
        self.assertFalse(collector.connections)
        collector.websocket_start(self.flow())
        self.assertFalse(collector.queue)
        collector.call_api = lambda *_: {"enabled": True, "generation": "second"}
        collector.sync_status()
        collector.websocket_start(self.flow())
        self.assertEqual(1, len(collector.queue))
        self.assertEqual(1, collector.queue[0][0]["sequence"])


if __name__ == "__main__": unittest.main()
