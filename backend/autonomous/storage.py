import json

def initialize(store):
    with store.transaction() as c:
        c.executescript('''
CREATE TABLE IF NOT EXISTS phone_schedules (schedule_id TEXT PRIMARY KEY, profile_id TEXT NOT NULL, config_json TEXT NOT NULL, next_due TEXT, enabled INTEGER NOT NULL, updated_at TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS phone_occurrences (occurrence_id TEXT PRIMARY KEY, schedule_id TEXT NOT NULL, due_at TEXT NOT NULL, state TEXT NOT NULL, error_code TEXT, UNIQUE(schedule_id,due_at));
CREATE TABLE IF NOT EXISTS phone_reports (session_id TEXT PRIMARY KEY, profile_id TEXT NOT NULL, stage TEXT NOT NULL, report_json TEXT NOT NULL DEFAULT '{}', attempts INTEGER NOT NULL DEFAULT 0, next_attempt TEXT, error_code TEXT, updated_at TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS phone_devices (device_id TEXT PRIMARY KEY, profile_id TEXT NOT NULL, token TEXT NOT NULL, updated_at TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS phone_notifications (notification_id TEXT PRIMARY KEY, session_id TEXT NOT NULL, device_id TEXT NOT NULL, state TEXT NOT NULL, attempts INTEGER NOT NULL DEFAULT 0, error_code TEXT, next_attempt TEXT);
''')

def call_record(row): return {**dict(row),**json.loads(row['call_options_json'])}
