"""Relay: one active, server-ordered route grant and a deliberately fake sensor."""

import uuid
from base import BaseService, Problem, fields, integer, text, run

POINTS = [
    ("North depot", 28.6139, 77.2090),
    ("Market crossing", 28.6150, 77.2120),
    ("Library corner", 28.6170, 77.2140),
    ("Collection door", 28.6180, 77.2170),
]


class Service(BaseService):
    KIND = "relay"

    def seed(self):
        return {
            "epoch": 0,
            "trip": None,
            "fixes": [],
            "nextSequence": 1,
            "accepted": None,
            "events": [],
        }

    def view(self, state, now):
        fix = state["accepted"]
        trip = state["trip"]
        age = None if fix is None else max(0, now - fix["capturedAt"])
        receipt = None if fix is None else max(0, now - fix["receivedAt"])
        return {
            "epoch": state["epoch"],
            "trip": trip,
            "accepted": fix,
            "fixAgeMs": age,
            "receiptAgeMs": receipt,
            "stale": age is not None and age > 30000,
            "nextSequence": state["nextSequence"],
            "route": [{"name": p[0], "lat": p[1], "lon": p[2]} for p in POINTS],
            "events": state["events"][-8:],
        }

    def capture(self, state, now):
        trip = state["trip"]
        if not trip or trip["phase"] != "ACTIVE":
            return {"accepted": False, "reason": "No active route"}
        seq = state["nextSequence"]
        point = POINTS[min(seq - 1, len(POINTS) - 1)]
        fix = {
            "tripId": trip["id"],
            "epoch": state["epoch"],
            "sequence": seq,
            "capturedAt": now,
            "name": point[0],
            "lat": point[1],
            "lon": point[2],
        }
        state["fixes"].append(fix)
        state["nextSequence"] += 1
        # Bound this teaching fixture; do not silently discard unacknowledged fixes.
        return {"accepted": True, "fix": fix}

    def publish(self, state, args, now):
        fields(args, {"tripId", "epoch", "sequence"})
        text(args["tripId"], 36)
        integer(args["epoch"], 1)
        integer(args["sequence"], 1, 100)
        trip = state["trip"]
        if (
            not trip
            or trip["id"] != args["tripId"]
            or state["epoch"] != args["epoch"]
            or trip["phase"] != "ACTIVE"
        ):
            return {"accepted": False, "reason": "Route grant is no longer active"}
        fix = next((f for f in state["fixes"] if all(f[k] == args[k] for k in args)), None)
        if not fix:
            return {"accepted": False, "reason": "Unknown fixture fix"}
        current = state["accepted"]
        if current and args["sequence"] <= current["sequence"]:
            return {
                "accepted": False,
                "reason": "Duplicate or older sequence; observation unchanged",
            }
        state["accepted"] = {**fix, "receivedAt": now}
        state["events"].append(f"Accepted fix {fix['sequence']} at {fix['name']}")
        return {"accepted": True, "sequence": fix["sequence"]}

    def apply(self, state, action, args, now):
        if action == "start":
            fields(args, {})
            if state["trip"] and state["trip"]["phase"] == "ACTIVE":
                return {"accepted": False, "reason": "End the active route first"}
            state["epoch"] += 1
            state["trip"] = {"id": str(uuid.uuid4()), "phase": "ACTIVE", "startedAt": now}
            state["accepted"] = None
            state["fixes"] = []
            state["nextSequence"] = 1
            state["events"] = ["Route started"]
            return {"accepted": True, "trip": state["trip"], "epoch": state["epoch"]}
        if action in {"capture", "next"}:
            fields(args, {"tripId", "epoch"})
            text(args["tripId"], 36)
            integer(args["epoch"], 1)
            trip = state["trip"]
            if (
                not trip
                or trip["id"] != args["tripId"]
                or state["epoch"] != args["epoch"]
                or trip["phase"] != "ACTIVE"
            ):
                return {"accepted": False, "reason": "Route grant is no longer active"}
            if state["nextSequence"] > 100:
                return {"accepted": False, "reason": "Fixture limit reached; end this route"}
            captured = self.capture(state, now)
            if action == "capture" or not captured["accepted"]:
                return captured
            return self.publish(
                state, {k: captured["fix"][k] for k in ["tripId", "epoch", "sequence"]}, now
            )
        if action == "publish":
            return self.publish(state, args, now)
        if action == "end":
            fields(args, {"tripId", "epoch"})
            text(args["tripId"], 36)
            integer(args["epoch"], 1)
            trip = state["trip"]
            if not trip or trip["id"] != args["tripId"] or state["epoch"] != args["epoch"]:
                return {"accepted": False, "reason": "Not the current route grant"}
            trip["phase"] = "ENDED"
            state["events"].append("Route ended; later fixes fenced")
            return {"accepted": True, "phase": "ENDED"}
        raise Problem("Unknown action")


if __name__ == "__main__":
    run(Service, 8914)
