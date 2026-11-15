"""Tayfa arama sinyalleşmesi: asenkron olay merkezi.

Arama kurulum mesajları (SDP teklifi/yanıtı, ICE adayları, çalıyor, kapatma...) üyeler arasında
bu merkez üzerinden iletilir. Ses ve görüntü cihazlar arasında WebRTC (DTLS-SRTP) ile uçtan uca
şifrelidir; sunucu yalnızca kurulum mesajlarını taşır.

İki teslim yolu vardır, ikisi aynı kuyruğu kullanır:
  * WebSocket (/bayx/call/ws): olay oluştuğu anda iletilir (yeni uygulamalar).
  * Uzun yoklama (/bayx/call/poll): eski uygulamalar ve WebSocket'in kurulamadığı ağlar için.

Teslimat teyitlidir: her olayın artan bir numarası vardır; istemci aldığı son numarayı bildirene
kadar olay kuyrukta kalır. Bağlantı yarıda kopsa da olay kaybolmaz.
"""

import asyncio
import time

CALL_TYPES = {"invite", "answer", "candidate", "hangup", "reject", "busy", "ringing", "reoffer", "reanswer"}
CALL_TTL = 60  # saniye: bu süreden eski olaylar teslim edilmez
POLL_SECONDS = 25
QUEUE_MAX = 300


class CallHub:
    def __init__(self):
        self._queues = {}  # kullanıcı -> [olay, ...]
        self._waiters = {}  # kullanıcı -> {asyncio.Event, ...}
        # Sunucu yeniden başlasa da numaralar geriye gitmesin (istemci son numarayı hatırlıyor).
        self._seq = int(time.time() * 1000)

    def _live(self, uid, now):
        queue = [e for e in self._queues.get(uid, []) if now - e["ts"] < CALL_TTL]
        if queue:
            self._queues[uid] = queue
        else:
            self._queues.pop(uid, None)
        return queue

    def publish(self, to, event):
        """Olayı alıcının kuyruğuna ekler ve bekleyen tüm bağlantılarını uyandırır."""
        now = time.time()
        self._seq += 1
        event = dict(event, id=self._seq, ts=now)
        queue = self._live(to, now)
        queue.append(event)
        self._queues[to] = queue[-QUEUE_MAX:]
        for waiter in self._waiters.get(to, ()):
            waiter.set()
        return event

    def pending(self, uid, after):
        """[after] numarasından sonraki olaylar; daha eskileri teyit edildiği için silinir."""
        now = time.time()
        queue = [e for e in self._live(uid, now) if e["id"] > after]
        if queue:
            self._queues[uid] = queue
        else:
            self._queues.pop(uid, None)
        return [dict(e, age=round(now - e["ts"], 1)) for e in queue]

    def take_all(self, uid):
        """Teyit göndermeyen eski istemciler için: olayları teslim edip kuyruğu boşaltır."""
        now = time.time()
        queue = self._live(uid, now)
        self._queues.pop(uid, None)
        return [dict(e, age=round(now - e["ts"], 1)) for e in queue]

    def subscribe(self, uid):
        """Bu kullanıcıya olay gelince tetiklenecek bir işaret döndürür (bağlantı boyunca)."""
        event = asyncio.Event()
        self._waiters.setdefault(uid, set()).add(event)
        return event

    def unsubscribe(self, uid, event):
        waiters = self._waiters.get(uid)
        if waiters is not None:
            waiters.discard(event)
            if not waiters:
                self._waiters.pop(uid, None)

    async def poll(self, uid, after=None, timeout=POLL_SECONDS):
        """Uzun yoklama: bekleyen olay varsa hemen, yoksa ilk olayda ya da süre dolunca döner."""
        deadline = time.monotonic() + timeout
        signal = self.subscribe(uid)  # kontrolden ÖNCE: arada gelen olay kaçmasın
        try:
            while True:
                signal.clear()
                events = self.pending(uid, after) if after is not None else self.take_all(uid)
                if events:
                    return events
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    return []
                try:
                    await asyncio.wait_for(signal.wait(), remaining)
                except asyncio.TimeoutError:
                    pass
        finally:
            self.unsubscribe(uid, signal)

    def online(self, uid):
        """Kullanıcının şu an bekleyen (bağlı) bir cihazı var mı?"""
        return bool(self._waiters.get(uid))
