"""Allow real (non-test) incidents. The API decides who may create one (ENVIRONMENT=beta), not the schema.

Idempotent like 0009-0012: the CHECK (is_test) constraint is found by its definition, whatever its name.
"""
from alembic import op

revision = "0013"
down_revision = "0012"


def upgrade():
    op.execute("""
        DO $$
        DECLARE c record;
        BEGIN
            FOR c IN SELECT conname FROM pg_constraint
                     WHERE conrelid = 'incidents'::regclass AND contype = 'c'
                       AND pg_get_constraintdef(oid) = 'CHECK (is_test)'
            LOOP
                EXECUTE format('ALTER TABLE incidents DROP CONSTRAINT %I', c.conname);
            END LOOP;
        END $$;
    """)
    # The per-hour SOS limit counts an owner's recent real incidents.
    op.execute("CREATE INDEX IF NOT EXISTS ix_incidents_owner_started ON incidents(victim_user_id, started_at DESC)")


def downgrade():
    op.execute("DROP INDEX IF EXISTS ix_incidents_owner_started")
    # Refuses (and so keeps the data) if real incidents exist.
    op.execute("ALTER TABLE incidents ADD CONSTRAINT incidents_is_test_check CHECK (is_test)")
