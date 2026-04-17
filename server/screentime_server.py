"""Screen time tracker.

Listens on 127.0.0.1:8767. Mounted by nginx at /api/screentime/*.
Stores to /root/.screentime/data.json.

Two data sources coexist:
  1. Toggle-based sessions (manual: GET /toggle/{app})
  2. Bulk reports from Android app (POST /report)

Both end up in the same per-day dict; /today merges them transparently.

Endpoints:
  GET  /api/screentime/toggle/{app}      — toggle open/close, returns new state
  POST /api/screentime/report            — bulk-report today's app totals
  GET  /api/screentime/today             — merged summary of all apps today
  GET  /api/screentime/today/{app}       — detailed sessions for one app today
  GET  /api/screentime/status/{app}      — is the app currently "open"?
  GET  /api/screentime/all               — last 7 days summary
"""
from __future__ import annotations

import json
from datetime import date, datetime, timedelta
from pathlib import Path
from threading import Lock

from starlette.applications import Starlette
from starlette.responses import JSONResponse
from starlette.routing import Route

STORE_DIR = Path('/root/.screentime')
STORE_FILE = STORE_DIR / 'data.json'
HEARTBEAT_FILE = STORE_DIR / 'heartbeat.json'
SESSIONS_FILE = STORE_DIR / 'sessions.json'
_lock = Lock()

# Data shape:
# {
#   "2026-04-08": {
#     "__reported__": {
#       "com.tencent.mm": {"label": "微信", "total_sec": 2103, "updated": "..."},
#       ...
#     },
#     "微信": {"sessions": [...], "total_sec": 0}   ← toggle-based
#   }
# }


def _now_iso() -> str:
    return datetime.now().isoformat(timespec='seconds')


def _today() -> str:
    return date.today().isoformat()


def _load() -> dict:
    if not STORE_FILE.exists():
        return {}
    try:
        return json.loads(STORE_FILE.read_text(encoding='utf-8'))
    except json.JSONDecodeError:
        return {}


def _save(data: dict) -> None:
    STORE_DIR.mkdir(parents=True, exist_ok=True)
    STORE_FILE.write_text(
        json.dumps(data, ensure_ascii=False, indent=2),
        encoding='utf-8',
    )


def _ensure_toggle(data: dict, day: str, app: str) -> dict:
    if day not in data:
        data[day] = {}
    if app not in data[day]:
        data[day][app] = {'sessions': [], 'total_sec': 0}
    return data[day][app]


def _recompute_total(app_data: dict) -> int:
    total = 0
    for s in app_data['sessions']:
        if s.get('end'):
            try:
                start = datetime.fromisoformat(s['start'])
                end = datetime.fromisoformat(s['end'])
                total += max(0, int((end - start).total_seconds()))
            except Exception:
                pass
    return total


def _live_total(app_data: dict) -> int:
    total = app_data.get('total_sec', 0)
    if app_data['sessions'] and app_data['sessions'][-1].get('end') is None:
        try:
            start = datetime.fromisoformat(app_data['sessions'][-1]['start'])
            total += max(0, int((datetime.now() - start).total_seconds()))
        except Exception:
            pass
    return total


def _is_open(app_data: dict | None) -> bool:
    if not app_data or not isinstance(app_data, dict) or 'sessions' not in app_data:
        return False
    s = app_data['sessions']
    return bool(s and s[-1].get('end') is None)


def _merged_today(data: dict, day: str) -> dict:
    """Merge toggle and reported data for a day into a single {name: info} dict."""
    day_data = data.get(day, {})
    apps = {}
    # Toggle-based entries (everything except __reported__)
    for k, v in day_data.items():
        if k == '__reported__':
            continue
        if not isinstance(v, dict) or 'sessions' not in v:
            continue
        total = _live_total(v)
        apps[k] = {
            'total_sec': total,
            'total_min': round(total / 60, 1),
            'session_count': len(v['sessions']),
            'currently_open': _is_open(v),
            'source': 'toggle',
        }
    # Reported entries from Android app — keyed by label, fallback to package
    reported = day_data.get('__reported__', {})
    for pkg, info in reported.items():
        label = info.get('label') or pkg
        total = int(info.get('total_sec', 0))
        # If a toggle entry with same name already exists, take the larger
        if label in apps:
            if total > apps[label]['total_sec']:
                apps[label]['total_sec'] = total
                apps[label]['total_min'] = round(total / 60, 1)
                apps[label]['source'] = 'merged'
        else:
            apps[label] = {
                'total_sec': total,
                'total_min': round(total / 60, 1),
                'package': pkg,
                'updated': info.get('updated'),
                'source': 'reported',
            }
    return apps


# ─────────────────────────────────────────────
# Handlers
# ─────────────────────────────────────────────

async def toggle(request):
    app = request.path_params['app']
    with _lock:
        data = _load()
        day = _today()
        app_data = _ensure_toggle(data, day, app)
        now = _now_iso()
        sessions = app_data['sessions']

        if _is_open(app_data):
            sessions[-1]['end'] = now
            app_data['total_sec'] = _recompute_total(app_data)
            _save(data)
            return JSONResponse({
                'app': app,
                'action': 'closed',
                'time': now,
                'total_sec_today': app_data['total_sec'],
                'total_min_today': round(app_data['total_sec'] / 60, 1),
            })
        else:
            sessions.append({'start': now, 'end': None})
            _save(data)
            return JSONResponse({
                'app': app,
                'action': 'opened',
                'time': now,
                'total_sec_today_so_far': app_data.get('total_sec', 0),
            })


async def report(request):
    """Receive bulk report from Android app.

    Body: {"date": "2026-04-08"?, "source": "..."?, "apps": {pkg: {label, total_sec}}}
    """
    try:
        body = await request.json()
    except Exception:
        return JSONResponse({'error': 'invalid json'}, status_code=400)

    apps_in = body.get('apps') or {}
    if not isinstance(apps_in, dict):
        return JSONResponse({'error': 'apps must be a dict'}, status_code=400)

    day = body.get('date') or _today()
    now = _now_iso()
    source = body.get('source', 'unknown')

    with _lock:
        data = _load()
        if day not in data:
            data[day] = {}
        if '__reported__' not in data[day]:
            data[day]['__reported__'] = {}
        reported_bucket = data[day]['__reported__']

        accepted = 0
        for pkg, info in apps_in.items():
            if not isinstance(info, dict):
                continue
            try:
                total_sec = int(info.get('total_sec', 0))
            except (TypeError, ValueError):
                continue
            if total_sec < 0:
                continue
            label = info.get('label') or pkg
            reported_bucket[pkg] = {
                'label': label,
                'total_sec': total_sec,
                'updated': now,
            }
            accepted += 1

        _save(data)

    return JSONResponse({
        'ok': True,
        'date': day,
        'accepted': accepted,
        'source': source,
        'received_at': now,
    })


async def today(request):
    with _lock:
        data = _load()
    day = _today()
    apps = _merged_today(data, day)
    sorted_apps = dict(sorted(apps.items(), key=lambda kv: -kv[1]['total_sec']))
    grand_total = sum(a['total_sec'] for a in apps.values())
    return JSONResponse({
        'date': day,
        'grand_total_sec': grand_total,
        'grand_total_min': round(grand_total / 60, 1),
        'app_count': len(apps),
        'apps': sorted_apps,
    })


async def today_app(request):
    app = request.path_params['app']
    with _lock:
        data = _load()
    day = _today()
    apps = _merged_today(data, day)
    info = apps.get(app)
    if not info:
        return JSONResponse({
            'date': day, 'app': app, 'sessions': [],
            'total_sec': 0, 'total_min': 0, 'currently_open': False,
        })
    # Try to attach raw sessions if toggle-based
    raw = data.get(day, {}).get(app, {})
    sessions = raw.get('sessions', []) if isinstance(raw, dict) else []
    return JSONResponse({
        'date': day,
        'app': app,
        'sessions': sessions,
        'total_sec': info['total_sec'],
        'total_min': info['total_min'],
        'session_count': len(sessions),
        'currently_open': info.get('currently_open', False),
        'source': info.get('source'),
    })


async def status(request):
    app = request.path_params['app']
    with _lock:
        data = _load()
    day = _today()
    raw = data.get(day, {}).get(app)
    return JSONResponse({
        'app': app,
        'open': _is_open(raw),
        'date': day,
    })


def _load_heartbeat() -> dict:
    if not HEARTBEAT_FILE.exists():
        return {}
    try:
        return json.loads(HEARTBEAT_FILE.read_text(encoding='utf-8'))
    except json.JSONDecodeError:
        return {}


def _save_heartbeat(hb: dict) -> None:
    STORE_DIR.mkdir(parents=True, exist_ok=True)
    HEARTBEAT_FILE.write_text(
        json.dumps(hb, ensure_ascii=False, indent=2), encoding='utf-8',
    )


def _load_sessions() -> list:
    if not SESSIONS_FILE.exists():
        return []
    try:
        return json.loads(SESSIONS_FILE.read_text(encoding='utf-8'))
    except json.JSONDecodeError:
        return []


def _save_sessions(sessions: list) -> None:
    STORE_DIR.mkdir(parents=True, exist_ok=True)
    SESSIONS_FILE.write_text(
        json.dumps(sessions, ensure_ascii=False, indent=2), encoding='utf-8',
    )


async def heartbeat_post(request):
    """Receive heartbeat from phone/PC.

    Body: {"current_app": "Claude"|null, "screen_on": true/false, "source": "..."}

    Tracks app switches in sessions.json automatically.
    """
    try:
        body = await request.json()
    except Exception:
        return JSONResponse({'error': 'invalid json'}, status_code=400)

    now = _now_iso()
    new_app = body.get('current_app')  # may be None
    screen_on = body.get('screen_on', True)
    source = body.get('source', 'unknown')

    with _lock:
        hb = _load_heartbeat()
        old_app = hb.get('current_app')

        # Update heartbeat state
        hb['current_app'] = new_app if screen_on else None
        hb['screen_on'] = screen_on
        hb['timestamp'] = now
        hb['source'] = source
        _save_heartbeat(hb)

        # Track app switches in sessions
        sessions = _load_sessions()
        today = _today()

        effective_app = new_app if screen_on else None
        app_changed = old_app != effective_app

        # Find the most recent open session (regardless of date)
        open_session = None
        for s in reversed(sessions):
            if s.get('end') is None:
                open_session = s
                break

        # Cross-midnight handling: if there is an open session whose date is
        # not today, close it at end-of-day and (if the same app is still in
        # foreground) start a fresh session dated today. Without this, an app
        # that spans midnight never gets a session on the new day, which makes
        # /sessions (filtered by today) return empty.
        if open_session is not None and open_session.get('date') != today:
            # Close yesterday's session at 23:59:59 of yesterday so totals are
            # attributed to the correct day.
            try:
                old_date = datetime.fromisoformat(open_session['date'])
                midnight_close = old_date.replace(
                    hour=23, minute=59, second=59,
                ).isoformat(timespec='seconds')
            except Exception:
                midnight_close = now
            open_session['end'] = midnight_close

            # If user is still in an app right now, open a new session on today
            if effective_app:
                sessions.append({
                    'app': effective_app,
                    'start': now,
                    'end': None,
                    'date': today,
                })
            # Re-fetch open_session since we just rolled it
            open_session = sessions[-1] if (
                sessions and sessions[-1].get('end') is None
            ) else None

        # Normal app switch within today
        if app_changed:
            # Close the last open session for old_app today
            if old_app:
                for s in reversed(sessions):
                    if (
                        s.get('app') == old_app
                        and s.get('end') is None
                        and s.get('date') == today
                    ):
                        s['end'] = now
                        break
            # Open a new session for the new app
            if effective_app and (
                open_session is None or open_session.get('app') != effective_app
            ):
                sessions.append({
                    'app': effective_app,
                    'start': now,
                    'end': None,
                    'date': today,
                })

        _save_sessions(sessions)

    return JSONResponse({'ok': True, 'received_at': now})


async def heartbeat_get(request):
    """Return latest heartbeat state."""
    with _lock:
        hb = _load_heartbeat()
    if not hb:
        return JSONResponse({
            'current_app': None,
            'screen_on': False,
            'timestamp': None,
            'idle_seconds': None,
        })
    idle = None
    if hb.get('timestamp'):
        try:
            ts = datetime.fromisoformat(hb['timestamp'])
            idle = max(0, int((datetime.now() - ts).total_seconds()))
        except Exception:
            pass
    return JSONResponse({
        'current_app': hb.get('current_app'),
        'screen_on': hb.get('screen_on', False),
        'last_active': hb.get('timestamp'),
        'idle_seconds': idle,
        'source': hb.get('source'),
    })


async def heartbeat_handler(request):
    if request.method == 'POST':
        return await heartbeat_post(request)
    return await heartbeat_get(request)


async def sessions_get(request):
    """Return today's app switch log."""
    limit = 50
    try:
        limit = int(request.query_params.get('limit', 50))
        limit = max(1, min(limit, 100))
    except (TypeError, ValueError):
        pass
    with _lock:
        sessions = _load_sessions()
    today = _today()
    today_sessions = [s for s in sessions if s.get('date') == today]
    # Return most recent first
    today_sessions.reverse()
    return JSONResponse({
        'date': today,
        'count': len(today_sessions),
        'sessions': today_sessions[:limit],
    })


async def all_days(request):
    """Last 7 days summary."""
    with _lock:
        data = _load()
    today_d = date.today()
    out = {}
    for i in range(7):
        d = (today_d - timedelta(days=i)).isoformat()
        if d not in data:
            continue
        apps = _merged_today(data, d)
        if not apps:
            continue
        out[d] = {
            'grand_total_min': round(sum(a['total_sec'] for a in apps.values()) / 60, 1),
            'apps': dict(sorted(apps.items(), key=lambda kv: -kv[1]['total_sec'])),
        }
    return JSONResponse(out)


routes = [
    Route('/api/screentime/toggle/{app:path}', toggle, methods=['GET']),
    Route('/api/screentime/report', report, methods=['POST']),
    Route('/api/screentime/heartbeat', heartbeat_handler, methods=['GET', 'POST']),
    Route('/api/screentime/sessions', sessions_get, methods=['GET']),
    Route('/api/screentime/today', today, methods=['GET']),
    Route('/api/screentime/today/{app:path}', today_app, methods=['GET']),
    Route('/api/screentime/status/{app:path}', status, methods=['GET']),
    Route('/api/screentime/all', all_days, methods=['GET']),
]

app = Starlette(routes=routes)


if __name__ == '__main__':
    import uvicorn
    uvicorn.run(app, host='127.0.0.1', port=8767, log_level='info')
