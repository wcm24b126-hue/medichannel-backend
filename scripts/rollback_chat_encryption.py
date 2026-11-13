#!/usr/bin/env python3
"""
MediChannel Consultation Chat Decryption Rollback Script
Disaster recovery script: decrypts AES-256-GCM 'v1:' records in chat_messages
back to plaintext if an authorized rollback is necessary.
Requires:
  ENCRYPTION_KEY environment variable (Base64-encoded 32-byte key)
  DB_URL, DB_USERNAME, DB_PASSWORD or local default connection.
"""

import os
import sys
import base64
import psycopg2
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

ENC_PREFIX = "v1:"
IV_LENGTH = 12

def main():
    enc_key_b64 = os.environ.get("ENCRYPTION_KEY")
    if not enc_key_b64:
        print("ERROR: ENCRYPTION_KEY environment variable must be set.")
        sys.exit(1)

    key = base64.b64decode(enc_key_b64)
    if len(key) != 32:
        print("ERROR: ENCRYPTION_KEY must be exactly 32 bytes.")
        sys.exit(1)

    aesgcm = AESGCM(key)

    db_host = os.environ.get("DB_HOST", "127.0.0.1")
    db_port = os.environ.get("DB_PORT", "55432")
    db_name = os.environ.get("DB_NAME", "medichannel_demo")
    db_user = os.environ.get("DB_USER", "postgres")
    db_pass = os.environ.get("DB_PASS", "local_test_only_password")

    conn = psycopg2.connect(
        host=db_host, port=db_port, dbname=db_name, user=db_user, password=db_pass
    )
    cur = conn.cursor()

    cur.execute("SELECT id, content FROM chat_messages WHERE content LIKE 'v1:%' FOR UPDATE;")
    rows = cur.fetchall()
    print(f"Found {len(rows)} encrypted chat records to rollback.")

    rollbacked = 0
    for msg_id, content in rows:
        try:
            raw = base64.b64decode(content[len(ENC_PREFIX):])
            iv = raw[:IV_LENGTH]
            ciphertext = raw[IV_LENGTH:]
            plaintext = aesgcm.decrypt(iv, ciphertext, None).decode('utf-8')

            cur.execute("UPDATE chat_messages SET content = %s WHERE id = %s;", (plaintext, msg_id))
            rollbacked += 1
        except Exception as e:
            print(f"Skipping record {msg_id}: decryption error {e}")

    conn.commit()
    cur.close()
    conn.close()

    print(f"Successfully rolled back {rollbacked} records to plaintext.")

if __name__ == "__main__":
    main()
