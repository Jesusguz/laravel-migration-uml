# SchemaToUML

[![JetBrains Plugin](https://img.shields.io/badge/JetBrains-Marketplace-blue.svg)](https://plugins.jetbrains.com)
[![Ko-Fi](https://img.shields.io/badge/Buy%20me%20a%20coffee-Ko--fi-F16061?style=flat&logo=ko-fi&logoColor=white)](https://ko-fi.com/jesusguz)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

Generador rápido, offline y privado de diagramas Entidad-Relación (ERD) en sintaxis Mermaid y Draw.io directamente desde migraciones de Laravel en PhpStorm / IntelliJ IDEA.

---

## Características

- **100% Offline y Privado:** Análisis estático local sin envío de esquemas a APIs externas ni telemetría.
- **Procesamiento por Directorios:** Clic derecho sobre carpetas completas (por ejemplo, `database/migrations`) para mapear bases de datos enteras al portapapeles.
- **Detección Automática de Relaciones:** Infiere claves foráneas (`constrained()`, `foreign()`) y convenciones nativas de Laravel (`*_id`).
- **Extracción Detallada de Metadatos:** Identifica PKs, FKs, restricciones únicas compuestas, valores por defecto y longitud de campos.
- **Compatible con Draw.io y Mermaid:** Pega la salida directamente en [Mermaid Live Editor](https://mermaid.live), GitHub, Notion o Draw.io (*Insertar -> Avanzado -> Mermaid*).

---

## Atajos y Uso

1. Haz clic derecho sobre un archivo de migración o la carpeta `database/migrations`.
2. Selecciona **«Copiar Migración como Mermaid ERD»** (o usa el atajo `Shift + Alt + M`).
3. Pega el contenido directamente en tu visor Mermaid o herramienta de diagramación favorita.

---

## Soporte y Donaciones

Si SchemaToUML te ahorra tiempo documentando arquitecturas de bases de datos, puedes apoyar el desarrollo continuo con un café:

[![Ko-Fi](https://ko-fi.com/img/githubbutton_sm.svg)](https://ko-fi.com/jesusguz)

---

## Licencia

Distribuido bajo la licencia [MIT](LICENSE).
