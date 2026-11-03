#!/usr/bin/env python3
"""Save local database credentials without putting passwords in shell history."""

import getpass
import os
from pathlib import Path


def escape(value):
    return (value.replace("\\", "\\\\")
            .replace("\n", "\\n").replace("\r", "\\r")
            .replace("\t", "\\t").replace(" ", "\\ "))


def main():
    target = Path(__file__).resolve().parent.parent / "application-local.properties"
    if target.exists():
        raise SystemExit(f"O arquivo {target.name} já existe. Edite-o para atualizar as credenciais.")

    runtime_password = getpass.getpass("Senha do fixflow_runtime: ")
    migrator_password = getpass.getpass("Senha do fixflow_migrator: ")
    if not runtime_password or not migrator_password:
        raise SystemExit("As senhas não podem ficar vazias. Nenhum arquivo foi criado.")

    values = {
        "DB_URL": "jdbc:postgresql://localhost:5432/fixflow",
        "DB_USERNAME": "fixflow_runtime",
        "DB_PASSWORD": runtime_password,
        "DB_MIGRATOR_USERNAME": "fixflow_migrator",
        "DB_MIGRATOR_PASSWORD": migrator_password,
    }
    descriptor = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8") as config:
        config.write("# Configuração local; não versionar.\n")
        for key, value in values.items():
            config.write(f"{key}={escape(value)}\n")
    print(f"Criado {target.name}. Execute o Maven na raiz do projeto.")


if __name__ == "__main__":
    main()
