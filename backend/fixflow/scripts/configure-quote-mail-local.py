#!/usr/bin/env python3
"""Create a persistent local encryption key for queued quote links."""
import base64
import os
from pathlib import Path
import secrets

config = Path(__file__).resolve().parent.parent / 'application-quote-mail-local.properties'
try:
    descriptor = os.open(config, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
except FileExistsError:
    raise SystemExit('Configuração já existe. Nenhum arquivo foi sobrescrito.')
with os.fdopen(descriptor, 'w') as output:
    output.write('fixflow.quotes.link-encryption-key=' + base64.b64encode(secrets.token_bytes(32)).decode() + '\n')
print('Proteção local dos links de orçamento configurada. Chave preservada em arquivo privado.')
