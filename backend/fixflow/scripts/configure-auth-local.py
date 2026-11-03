#!/usr/bin/env python3
"""Generate persistent development RSA keys without printing private material."""
import os
from pathlib import Path
import subprocess

project = Path(__file__).resolve().parent.parent
key_dir = project / ".local/auth"
config = project / "application-auth-local.properties"
key_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
os.chmod(key_dir, 0o700)
private = key_dir / "private.pem"
public = key_dir / "public.pem"
if private.exists() or public.exists() or config.exists():
    raise SystemExit("Configuração já existe. Nenhum arquivo foi sobrescrito.")
previous_mask = os.umask(0o077)
try:
    subprocess.run(["openssl", "genpkey", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:2048", "-out", str(private)], check=True, stderr=subprocess.DEVNULL)
    subprocess.run(["openssl", "pkey", "-in", str(private), "-pubout", "-out", str(public)], check=True)
    config.write_text("fixflow.auth.private-key=" + private.as_uri() + "\n"
                      "fixflow.auth.public-key=" + public.as_uri() + "\n"
                      "fixflow.auth.cookie-secure=false\n")
finally:
    os.umask(previous_mask)
print("Chaves RSA locais criadas. Configuração restrita a desenvolvimento HTTP local.")
