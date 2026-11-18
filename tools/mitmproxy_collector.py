"""mitmproxy 官方 addon：只复制主游戏已解压消息，在后台向本机工作台提交有序批次。"""

import base64
import collections
import datetime
import http.client
import ipaddress
import json
import logging
import os
import threading
import time
import urllib.parse
import uuid


class AnalyzerCollector:
    """网络 hook 不做 HTTP 等待；队列达到边界时明确停止采集，不修改游戏流量。"""

    def __init__(self, origin=None, hosts=None, path_prefixes=None):
        self.origin = origin or os.environ.get("PROTOLENS_ORIGIN", "http://127.0.0.1:18766")
        parsed = urllib.parse.urlsplit(self.origin)
        if parsed.scheme != "http" or parsed.hostname not in ("127.0.0.1", "localhost") or parsed.path not in ("", "/"):
            raise ValueError("工作台必须是本机 HTTP 回环地址")
        self.host = parsed.hostname
        self.port = parsed.port or 18766
        self.capture_hosts = self.target_list(hosts, "PROTOLENS_CAPTURE_HOSTS")
        self.path_prefixes = self.target_list(path_prefixes, "PROTOLENS_CAPTURE_PATH_PREFIXES")
        self.source = str(uuid.uuid4())
        self.sequence = 0
        self.queue = collections.deque()
        self.queued_bytes = 0
        self.lock = threading.RLock()
        self.wakeup = threading.Event()
        self.stopping = threading.Event()
        self.thread = None
        self.connections = {}
        self.token = None
        self.last_error = ""
        self.broken = False
        self.last_log = 0.0
        self.capture_enabled = True
        self.generation = None

    @staticmethod
    def target_list(explicit, variable):
        values = explicit if explicit is not None else json.loads(os.environ.get(variable, "[]"))
        if not isinstance(values, list) or len(values) > 32 or any(not isinstance(value, str) or not value.strip() for value in values):
            raise ValueError("采集目标必须是非空字符串组成的数组")
        return tuple(values)

    def matches(self, flow):
        host = flow.request.host.lower().rstrip(".")
        path = flow.request.path.split("?", 1)[0]
        for allowed in self.capture_hosts:
            try:
                ipaddress.ip_address(allowed)
                host_matches = host == allowed
            except ValueError:
                host_matches = host == allowed or host.endswith("." + allowed)
            if host_matches and any(path.startswith(prefix) for prefix in self.path_prefixes):
                return True
        return False

    def running(self):
        if not self.capture_hosts or not self.path_prefixes:
            raise ValueError("缺少采集目标，请通过工作台启动采集器并在设置中配置域名和路径")
        self.capture_enabled = False
        self.thread = threading.Thread(target=self.pump, name="protolens-bridge", daemon=True)
        self.thread.start()
        logging.info("协议工作台采集器已启动，目标 %s；域名 %s，路径前缀 %s", self.origin, self.capture_hosts, self.path_prefixes)

    def enqueue(self, event):
        with self.lock:
            if self.broken or self.stopping.is_set():
                return False
            encoded_size = len(json.dumps(event, ensure_ascii=True, separators=(",", ":")))
            if len(self.queue) >= 4096 or self.queued_bytes + encoded_size > 32 * 1024 * 1024:
                self.broken = True
                self.last_error = "实时队列超过 4096 事件或 32 MiB，已暂停采集。恢复后重启采集器并重进小游戏。"
                logging.error(self.last_error)
                return False
            self.sequence += 1
            event["sequence"] = self.sequence
            self.queue.append((event, encoded_size))
            self.queued_bytes += encoded_size
        self.wakeup.set()
        return True

    def websocket_start(self, flow):
        with self.lock:
            if not self.capture_enabled:
                return
            # 使用公开 HTTPFlow.id 标识连接；不同连接与收发方向绝不混用。
            if not self.matches(flow):
                return
            endpoint = flow.request.pretty_url.split("?", 1)[0]
            extensions = flow.response.headers.get("sec-websocket-extensions", "") if flow.response else ""
            if self.enqueue({"type": "open", "connection": flow.id, "endpoint": endpoint, "extensions": extensions}):
                self.connections[flow.id] = 0

    def websocket_message(self, flow):
        with self.lock:
            if flow.websocket and self.matches(flow):
                del flow.websocket.messages[:-1]
            if flow.id not in self.connections or flow.websocket is None:
                return
            message = flow.websocket.messages[-1]
            # 只保留当前消息作为 hook 上下文，避免代理自身无限累计历史；不改写网络载荷。
            del flow.websocket.messages[:-1]
            # 插入或已丢弃的消息不冒充真实线上数据；该 addon 不执行 drop、inject 或改写。
            if message.injected or message.dropped:
                return
            if int(message.type) != 2:
                return
            if len(message.content) > 16 * 1024 * 1024:
                self.enqueue({"type": "error", "message": "单条游戏消息超过 16 MiB，连接采集已暂停"})
                del self.connections[flow.id]
                return
            self.connections[flow.id] += 1
            stamp = datetime.datetime.fromtimestamp(message.timestamp, datetime.timezone.utc).isoformat()
            self.enqueue({"type": "packet", "connection": flow.id,
                          "direction": "CS" if message.from_client else "SC",
                          "frameId": str(self.connections[flow.id]), "time": stamp,
                          "packetBase64": base64.b64encode(message.content).decode("ascii")})

    def websocket_end(self, flow):
        with self.lock:
            if flow.id in self.connections:
                self.enqueue({"type": "close", "connection": flow.id})
                del self.connections[flow.id]

    def call_api(self, method, path, value=None):
        # 直接使用 HTTPConnection，不读取系统代理，避免递归捕获本机提交流量。
        connection = http.client.HTTPConnection(self.host, self.port, timeout=4)
        try:
            body = None if value is None else json.dumps(value, ensure_ascii=True, separators=(",", ":")).encode("utf-8")
            headers = {"Content-Type": "application/json"}
            if self.token:
                headers["X-Capture-Token"] = self.token
            connection.request(method, path, body=body, headers=headers)
            response = connection.getresponse()
            data = response.read(2 * 1024 * 1024)
            if response.status != 200:
                raise RuntimeError("工作台 HTTP %s：%s" % (response.status, data.decode("utf-8", "replace")[:1000]))
            return json.loads(data)
        finally:
            connection.close()

    def send_once(self):
        """只在服务端确认完整批次后出队；结果不明保留相同序号和内容，供幂等重试。"""
        if self.token is None:
            self.token = self.call_api("GET", "/api/live/config")["token"]
        with self.lock:
            if not self.queue:
                return False
            items = []
            bytes_in_batch = 0
            for event, size in self.queue:
                if len(items) >= 64 or (items and bytes_in_batch + size > 1024 * 1024):
                    break
                items.append(event)
                bytes_in_batch += size
        result = self.call_api("POST", "/api/live/batches", {"source": self.source, "events": items})
        if result.get("source") != self.source or result.get("acknowledged", 0) < items[-1]["sequence"]:
            raise RuntimeError("工作台未确认完整批次，保留队列")
        with self.lock:
            for event in items:
                if not self.queue or self.queue[0][0]["sequence"] != event["sequence"]:
                    self.broken = True
                    raise RuntimeError("采集队列顺序异常，停止采集")
                _, size = self.queue.popleft()
                self.queued_bytes -= size
        self.last_error = ""
        return True

    def sync_status(self):
        """停止只暂停复制，代理继续转发游戏；新采集轮次清空旧队列并等待新握手。"""
        status = self.call_api("GET", "/api/live/status")
        enabled = status.get("enabled", False)
        generation = status.get("generation")
        with self.lock:
            if enabled != self.capture_enabled or generation != self.generation:
                self.queue.clear()
                self.queued_bytes = 0
                self.sequence = 0
                self.source = str(uuid.uuid4())
                self.connections.clear()
                self.broken = False
            self.capture_enabled = enabled
            self.generation = generation

    def pump(self):
        next_heartbeat = 0.0
        next_status = 0.0
        while not self.stopping.is_set():
            try:
                if time.monotonic() >= next_status:
                    self.sync_status()
                    next_status = time.monotonic() + 1
                if not self.capture_enabled:
                    self.stopping.wait(0.25)
                    continue
                if time.monotonic() >= next_heartbeat:
                    self.enqueue({"type": "heartbeat"})
                    next_heartbeat = time.monotonic() + 2
                if self.send_once():
                    continue
            except Exception as failure:
                self.last_error = str(failure)
                # 错误限频输出，保留旧队列，不自动换 source 或丢弃尚未确认的数据。
                if time.monotonic() - self.last_log >= 10:
                    logging.error("协议采集提交失败：%s。若工作台重启或已停止，请重启采集器并重新进入小游戏。", failure)
                    self.last_log = time.monotonic()
                self.stopping.wait(1)
            self.wakeup.wait(0.25)
            self.wakeup.clear()

    def done(self):
        # mitmproxy 会先结束连接；最多等待最后批次两秒，不能无限阻塞退出。
        deadline = time.monotonic() + 2
        while time.monotonic() < deadline:
            with self.lock:
                remaining = len(self.queue)
            if not remaining:
                break
            self.wakeup.set()
            time.sleep(0.05)
        self.stopping.set()
        self.wakeup.set()
        if self.thread:
            self.thread.join(timeout=5)
        with self.lock:
            if self.queue:
                logging.error("采集器退出时仍有 %s 个事件未被工作台确认；不要将此记录视为完整抓包。", len(self.queue))


addons = [AnalyzerCollector()]
