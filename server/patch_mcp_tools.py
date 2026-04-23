#!/usr/bin/env python3
"""Idempotent patcher: adds screentime_timeline / screentime_sleep_log /
screentime_history_sessions to the MCP server file (nowhere_server.py).

Run multiple times safely — uses a marker line to detect existing patches
and skips. Writes a timestamped .bak next to the original on success.
"""
from __future__ import annotations

import re
import sys
import time
from pathlib import Path

MARKER = '# ─── screentime v2 tools (timeline / sleep_log / history_sessions) ───'

PATCH = '''
# ─── screentime v2 tools (timeline / sleep_log / history_sessions) ───
@mcp.tool()
def screentime_timeline(
    day: str = "",
    start_hour: int = -1,
    end_hour: int = -1,
    min_duration_sec: int = 30,
) -> dict:
    """Get the chronological app session timeline for a specific day.

    Best for answering questions like 'what did I do yesterday afternoon?'
    or 'when did I first pick up my phone today?'. Returns sessions
    ordered by start time, plus the first and last activity timestamps
    of the day and the total active minutes.

    Note: requires the phone's Screentime app with foreground heartbeat
    service running (v2.10+). Sessions are tracked at ~60s granularity.

    Args:
        day: Date in YYYY-MM-DD format. Empty string means today.
        start_hour: Hour 0-23 to filter from. -1 = no filter.
        end_hour: Hour 0-23 to filter to (exclusive). -1 = no filter.
        min_duration_sec: Skip sessions shorter than this. Default 30s.
    """
    if not day:
        day = date.today().isoformat()
    min_dur = max(0, int(min_duration_sec))
    sessions = _st_load_sessions()
    now = datetime.now()
    picked = []
    for s in sessions:
        if s.get('date') != day:
            continue
        try:
            start = datetime.fromisoformat(s['start'])
        except Exception:
            continue
        if s.get('end'):
            try:
                end = datetime.fromisoformat(s['end'])
            except Exception:
                end = now
        else:
            end = now
        dur = max(0, int((end - start).total_seconds()))
        if dur < min_dur:
            continue
        if start_hour >= 0 and start.hour < start_hour:
            continue
        if end_hour >= 0 and start.hour >= end_hour:
            continue
        picked.append({
            'app': s.get('app'),
            'start': s['start'],
            'end': s.get('end'),
            'duration_min': round(dur / 60, 1),
            'duration_sec': dur,
        })
    picked.sort(key=lambda x: x['start'])
    first_active = picked[0]['start'] if picked else None
    last_active = (picked[-1]['end'] or picked[-1]['start']) if picked else None
    total = sum(s['duration_sec'] for s in picked)
    return {
        'date': day,
        'session_count': len(picked),
        'first_active': first_active,
        'last_active': last_active,
        'total_active_min': round(total / 60, 1),
        'sessions': picked,
    }


@mcp.tool()
def screentime_sleep_log(
    days: int = 7,
    gap_threshold_min: int = 180,
) -> dict:
    """Infer sleep/wake times for the last N days from session gaps.

    Finds the longest inactivity gap attributable to each calendar day
    and reports its start (bedtime) and end (wake time). A gap starting
    between 00:00 and 05:00 is attributed to the previous day (natural
    'last night' semantics — going to sleep at 01:30 on Tuesday belongs
    to Monday's sleep).

    Gaps shorter than gap_threshold_min are considered 'probably not
    sleep' (e.g. shower, meal) and filtered out.

    Note: requires the phone's Screentime app with foreground heartbeat
    service running (v2.10+).

    Args:
        days: Days back to report (1-30). Default 7.
        gap_threshold_min: Minimum gap minutes to count as sleep. Default 180 (3h).
    """
    days = max(1, min(int(days or 7), 30))
    thr_min = max(10, int(gap_threshold_min or 180))
    sessions = _st_load_sessions()
    # Build sorted list of (start, end) for every session that has an end
    now = datetime.now()
    entries = []
    for s in sessions:
        try:
            start = datetime.fromisoformat(s['start'])
        except Exception:
            continue
        if s.get('end'):
            try:
                end = datetime.fromisoformat(s['end'])
            except Exception:
                end = now
        else:
            end = now
        entries.append((start, end))
    entries.sort(key=lambda x: x[0])
    # Find gaps between consecutive sessions
    gaps = []
    for i in range(len(entries) - 1):
        gap_start = entries[i][1]
        gap_end = entries[i + 1][0]
        gap_min = (gap_end - gap_start).total_seconds() / 60
        if gap_min < thr_min:
            continue
        # Attribution: gaps that start before 05:00 belong to previous day
        if gap_start.hour < 5:
            attribution = (gap_start.date() - _timedelta(days=1)).isoformat()
        else:
            attribution = gap_start.date().isoformat()
        gaps.append({
            'attribution_date': attribution,
            'sleep': gap_start.isoformat(timespec='seconds'),
            'wake': gap_end.isoformat(timespec='seconds'),
            'gap_min': gap_min,
        })
    # For each of the last N days, pick the longest attributed gap
    today_d = date.today()
    out = []
    for i in range(days):
        d_str = (today_d - _timedelta(days=i)).isoformat()
        day_gaps = [g for g in gaps if g['attribution_date'] == d_str]
        if not day_gaps:
            out.append({'date': d_str, 'status': 'no_data'})
            continue
        best = max(day_gaps, key=lambda g: g['gap_min'])
        out.append({
            'date': d_str,
            'estimated_sleep': best['sleep'],
            'estimated_wake': best['wake'],
            'sleep_duration_hr': round(best['gap_min'] / 60, 2),
        })
    out.reverse()
    return {
        'days': out,
        'threshold_min': thr_min,
    }


@mcp.tool()
def screentime_history_sessions(day: str = "", limit: int = 50) -> dict:
    """Get the raw session log for any historical day (not just today).

    Unlike screentime_session_log which is today-only, this lets you
    inspect any day's app switch log. Each session has an app name,
    start time, end time (None if still open), and date.

    Args:
        day: Date in YYYY-MM-DD format. Empty = yesterday.
        limit: Max sessions to return (1-200). Default 50.
    """
    if not day:
        day = (date.today() - _timedelta(days=1)).isoformat()
    limit = max(1, min(int(limit or 50), 200))
    sessions = _st_load_sessions()
    day_sessions = [s for s in sessions if s.get('date') == day]
    day_sessions.sort(key=lambda x: x.get('start', ''))
    return {
        'date': day,
        'count': len(day_sessions),
        'sessions': day_sessions[:limit],
    }
'''


def find_target() -> Path | None:
    """Locate the MCP server file — look for nowhere_server.py containing
    screentime_session_log to be sure we patch the right place."""
    candidates = []
    for root in ('/root', '/home', '/opt', '/srv'):
        r = Path(root)
        if not r.exists():
            continue
        for p in r.rglob('nowhere_server.py'):
            if '.bak' in p.name:
                continue
            candidates.append(p)
        for p in r.rglob('memory_server_http.py'):
            if '.bak' in p.name:
                continue
            candidates.append(p)
    for c in candidates:
        try:
            text = c.read_text(encoding='utf-8', errors='ignore')
        except Exception:
            continue
        if 'screentime_session_log' in text and '@mcp.tool' in text:
            return c
    return None


def main() -> int:
    target = find_target()
    if target is None:
        print('ERROR: could not locate MCP server file with screentime tools',
              file=sys.stderr)
        print('Tried nowhere_server.py / memory_server_http.py in /root /home /opt /srv',
              file=sys.stderr)
        return 2

    text = target.read_text(encoding='utf-8')
    if MARKER in text:
        print(f'already patched (marker found), skipping: {target}')
        return 0

    # Insert before `if __name__ == '__main__':` or append if absent
    m = re.search(r"\n(if __name__ == ['\"]__main__['\"]:)\n", text)
    if m:
        new_text = text[:m.start() + 1] + PATCH + '\n\n' + text[m.start() + 1:]
    else:
        new_text = text.rstrip() + '\n\n' + PATCH + '\n'

    # Syntax check before writing
    import ast
    try:
        ast.parse(new_text)
    except SyntaxError as e:
        print(f'ERROR: patched file has syntax error: {e}', file=sys.stderr)
        return 3

    # Backup + write
    bak = target.with_suffix(f'.py.bak.{int(time.time())}')
    bak.write_text(text, encoding='utf-8')
    target.write_text(new_text, encoding='utf-8')
    print(f'patched OK: {target}')
    print(f'backup:      {bak}')
    return 0


if __name__ == '__main__':
    sys.exit(main())
