#!/usr/bin/env bash
set -euo pipefail

DB="$GITHUB_WORKSPACE/meter-reader-v12-final.db"
adb shell am force-stop "$PACKAGE_NAME" >/dev/null
sleep .6
adb exec-out run-as "$PACKAGE_NAME" cat databases/meter-reader.db > "$DB"
test -s "$DB"

python3 - "$DB" <<'PY'
import sqlite3,sys
p=sys.argv[1]
db=sqlite3.connect(p)
assert db.execute('pragma user_version').fetchone()[0] == 5

required={'metering_points','submissions','submission_items'}
tables={r[0] for r in db.execute("select name from sqlite_master where type='table'")}
assert required <= tables, (required,tables)

meter_cols={r[1] for r in db.execute('pragma table_info(meters)')}
reading_cols={r[1] for r in db.execute('pragma table_info(readings)')}
assert 'meteringPointId' in meter_cols
assert 'billingPeriod' in reading_cols

# The full preview flow replaces the cold-water device. Old and new physical devices
# must remain linked to the same logical metering point.
rows=db.execute("select id,previousMeterId,status,meteringPointId,integerDigits,fractionDigits from meters where name='Холодная вода' order by rowid").fetchall()
assert len(rows) >= 2, rows
old=next((r for r in rows if r[1] is None and r[2]=='closed'),None)
new=next((r for r in rows if old and r[1]==old[0] and r[2]=='active'),None)
assert old and new, rows
assert old[3] and new[3] == old[3], (old,new)
point=db.execute("select addressId,name,unit,kind,location,account,recipient from metering_points where id=?",(old[3],)).fetchone()
assert point is not None, old[3]

# Reading values are persisted as TEXT. Double may exist only as a compatibility/convenience
# field in the Kotlin model and is not the canonical persisted value.
rv_type=db.execute("select type from pragma_table_info('reading_values') where name='valueText'").fetchone()
assert rv_type and rv_type[0].upper() == 'TEXT', rv_type

# Submission persistence must be structurally separate from readings.
submission_cols={r[1] for r in db.execute('pragma table_info(submissions)')}
item_cols={r[1] for r in db.execute('pragma table_info(submission_items)')}
assert {'billingPeriod','submittedAt','snapshotText'} <= submission_cols, submission_cols
assert {'meteringPointId','meterId','zone','valueText'} <= item_cols, item_cols

print('v1.2 final DB model OK')
print('replacement point:', old[3], old[0], '->', new[0])
print('point:', point)
PY
