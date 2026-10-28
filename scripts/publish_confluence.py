#!/usr/bin/env python3
"""Publica docs/confluence/ en un espacio de Confluence Cloud.

Lee confluence-manifest.yml, convierte cada página a storage format y la crea o
actualiza. Es idempotente: se identifica cada página por su título dentro del
espacio, así que ejecutarlo dos veces actualiza en lugar de duplicar.

Confluence también es editable: el publicador sella en cada página una `content
property` con la versión que dejó y la huella de lo que generó, así que en la
corrida siguiente distingue «esto lo escribí yo» de «esto lo editó una persona» y
**conserva lo segundo** en vez de pisarlo. Eso es lo que permite corregir texto o
pegar la imagen de un diagrama directamente en Confluence.

  pip install pyyaml markdown

  export CONFLUENCE_BASE_URL="https://liverpool.atlassian.net"
  export CONFLUENCE_EMAIL="tu.correo@liverpool.com.mx"
  export CONFLUENCE_API_TOKEN="..."        # id.atlassian.com/manage-profile/security/api-tokens

  python3 scripts/publish_confluence.py --space <SPACE_KEY> --dry-run
  python3 scripts/publish_confluence.py --space <SPACE_KEY> --status
  python3 scripts/publish_confluence.py --space <SPACE_KEY>

El orden de publicación es descendente (padres antes que hijos) porque cada hijo
necesita el id de su padre.
"""

from __future__ import annotations

import argparse
import base64
import datetime
import hashlib
import html as html_lib
import http.client
import json
import os
import random
import re
import sys
import shutil
import subprocess
import tempfile
import time
import unicodedata
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

try:
    import yaml
except ImportError:
    sys.exit("Falta PyYAML. Instala con: pip install pyyaml markdown")

try:
    import markdown as md
except ImportError:
    sys.exit("Falta markdown. Instala con: pip install pyyaml markdown")


MANIFEST = Path(__file__).resolve().parent.parent / "docs" / "confluence" / "confluence-manifest.yml"

# Extensiones mínimas para que sobrevivan tablas GFM, bloques de código y anclas.
MD_EXTENSIONS = ["tables", "fenced_code", "sane_lists", "attr_list"]

# Front matter semántico. El contrato completo vive en .claude/skills/confluence-docs/SKILL.md.
REQUIRED_META = ("id", "title", "type", "status", "owner", "lastReviewed", "sources", "tags")
DOC_TYPES = {"overview", "reference", "how-to", "api", "explanation", "adr", "feature"}
DOC_STATUSES = {"current", "draft", "deprecated"}

# Formato antiguo: una página sin `summary` abre con estas tres secciones, en este orden.
# Con `summary` la cabecera la genera el publicador (panel + índice) y el preámbulo sobra.
HOUSE_PREAMBLE = ("Objetivo", "Alcance", "Audiencia")

# Una página de funcionalidad se lee siempre igual: estas secciones `#`, en este orden, y
# dentro de Soporte estas `##`. Es lo que permite a soporte saltar directo a lo suyo.
FEATURE_SECTIONS = ("En 30 segundos", "Cómo funciona", "Implementación", "Configuración",
                    "Soporte", "Referencias")
SUPPORT_SUBSECTIONS = ("Dónde mirar en producción", "Síntomas y acciones",
                       "cURLs de verificación", "Escalamiento")
FEATURE_LABELS = ("funcionalidad", "soporte")

# Por encima de esto una página de funcionalidad deja de leerse de una sentada. Avisa, no falla.
FEATURE_MAX_LINES = 450

# Avisos GFM (`> [!WARNING]`) → macro de panel de Confluence.
ALERT_MACROS = {"NOTE": "info", "TIP": "tip", "IMPORTANT": "note", "WARNING": "warning",
                "CAUTION": "warning"}

# Versión fija del renderizador de diagramas: npx la descarga una vez y la cachea.
MERMAID_CLI = "@mermaid-js/mermaid-cli@11.4.2"
MERMAID_CACHE = Path(__file__).resolve().parent / ".mermaid-cache"

# Página bajo la que --archive-orphans cuelga lo que salió del manifiesto.
ARCHIVE_TITLE = "Archivo (obsoleto)"
ARCHIVE_LABEL = "obsoleta"

# HTML que el storage format de Confluence no acepta y hay que escribir en Markdown.
# `br` no está: es sintaxis válida dentro de las etiquetas de un diagrama mermaid.
RAW_HTML_TAGS = {"details", "summary", "div", "span", "img", "table", "tr", "td", "th", "ul", "ol", "li"}


# ── front matter ─────────────────────────────────────────────────────────────


def split_frontmatter(text: str):
    """Separa el bloque YAML inicial del cuerpo Markdown.

    Los tags viven en el archivo pero no en la página publicada: Confluence no entiende
    front matter y lo renderizaría como un párrafo de texto plano al inicio del cuerpo.
    """
    if not text.startswith("---"):
        return {}, text
    m = re.match(r"^---\r?\n(.*?)\r?\n---[ \t]*\r?\n", text, flags=re.DOTALL)
    if not m:
        return {}, text
    return yaml.safe_load(m.group(1)) or {}, text[m.end():]


def validate_meta(meta: dict, raw_title: str) -> list:
    """Problemas del front matter de una página, en lenguaje humano. Vacío = correcto."""
    if not meta:
        return ["sin front matter"]
    problems = [f"falta '{key}'" for key in REQUIRED_META if not meta.get(key)]
    # El título es la clave de actualización en Confluence: si el archivo y el manifiesto
    # discrepan, el front matter está describiendo una página que no es la suya.
    if meta.get("title") and meta["title"] != raw_title:
        problems.append(f"title '{meta['title']}' != manifiesto '{raw_title}'")
    if meta.get("type") and meta["type"] not in DOC_TYPES:
        problems.append(f"type '{meta['type']}' no está en {sorted(DOC_TYPES)}")
    if meta.get("status") and meta["status"] not in DOC_STATUSES:
        problems.append(f"status '{meta['status']}' no está en {sorted(DOC_STATUSES)}")
    # La cabecera de una página de funcionalidad sale de `summary`: sin él no hay qué mostrar.
    if meta.get("type") == "feature" and not meta.get("summary"):
        problems.append("falta 'summary' (obligatorio en type: feature)")
    return problems


def headings(body: str, level: int) -> list:
    """Encabezados de un nivel, fuera de los bloques de código y sin su `{#ancla}`."""
    prose = re.sub(r"```.*?```", "", body, flags=re.DOTALL)
    marks = "#" * level
    return [re.sub(r"\s*\{#[^}]*\}\s*$", "", h).strip()
            for h in re.findall(rf"^{marks} (.+)$", prose, re.M)]


def validate_body(body: str, page_path: Path, docs_root: Path, declared_files: set,
                  meta: dict | None = None) -> list:
    """Problemas del cuerpo de una página. Vacío = correcto.

    Cubre lo que el conversor no puede arreglar solo y se degradaría en silencio:
    un enlace fuera del árbol publicado se convierte en texto plano (el lector pierde
    la referencia sin que nadie se entere), y el HTML crudo no sobrevive al storage
    format. La estructura se comprueba porque es lo que hace navegable el corpus: una
    página de funcionalidad con las secciones en otro orden obliga a buscarlas.
    """
    meta = meta or {}
    problems = []

    heads = headings(body, 1)
    if not meta.get("summary") and heads[:3] != list(HOUSE_PREAMBLE):
        problems.append(f"preámbulo: se esperaba {list(HOUSE_PREAMBLE)} (o un 'summary' en el "
                        f"front matter), hay {heads[:3] or 'nada'}")
    if meta.get("type") == "feature":
        if heads != list(FEATURE_SECTIONS):
            problems.append(f"secciones de funcionalidad: se esperaba {list(FEATURE_SECTIONS)}, "
                            f"hay {heads}")
        # Sin fences: un `# comentario` de bash no es una sección.
        support = re.split(r"^# ", re.sub(r"```.*?```", "", body, flags=re.DOTALL), flags=re.M)
        support = next((s for s in support if s.startswith("Soporte")), "")
        subs = headings(support, 2)
        if subs != list(SUPPORT_SUBSECTIONS):
            problems.append(f"subsecciones de Soporte: se esperaba {list(SUPPORT_SUBSECTIONS)}, "
                            f"hay {subs}")

    # Lo que hace fallar el render de un diagrama, detectado sin renderizar: mermaid no entiende
    # entidades HTML (`&lt;` se escribe `#lt;`), y en un sequenceDiagram un `;` termina la instrucción.
    for m in re.finditer(r"```mermaid\n(.*?)```", body, flags=re.DOTALL):
        src = m.group(1)
        if re.search(r"&(lt|gt|amp|quot);", src):
            problems.append("diagrama con entidad HTML (&lt; &gt; &amp;): en mermaid se escribe #lt; #gt; #amp;")
        if src.lstrip().startswith("sequenceDiagram") and re.search(r"(?<!#\w\w)(?<!#\w\w\w);", src):
            problems.append("sequenceDiagram con ';' en un texto: mermaid lo lee como fin de instrucción; usa ','")

    for m in re.finditer(r"^> \[!(\w+)\]", body, re.M):
        if m.group(1) not in ALERT_MACROS:
            problems.append(f"aviso '[!{m.group(1)}]' desconocido; usa {sorted(ALERT_MACROS)}")

    for m in re.finditer(r"\]\(([^)#]+\.md)(?:#[^)]*)?\)", body):
        target = (page_path.parent / m.group(1)).resolve()
        if not target.exists():
            problems.append(f"enlace roto -> {m.group(1)}")
        elif not str(target).startswith(str(docs_root)):
            problems.append(f"enlace fuera del árbol publicado (se perdería como texto plano) "
                            f"-> {m.group(1)}; cítalo como `ruta` en vez de enlazarlo")
        elif str(target.relative_to(docs_root)) not in declared_files:
            problems.append(f"enlace a una página que no está en el manifiesto -> {m.group(1)}")

    # Fuera de fences y de `código en línea`, donde sí es contenido legítimo.
    prose = re.sub(r"`[^`]*`", "", re.sub(r"```.*?```", "", body, flags=re.DOTALL))
    for tag in sorted({m.group(1).lower() for m in re.finditer(r"<(\w+)[\s/>]", prose)} & RAW_HTML_TAGS):
        problems.append(f"HTML crudo <{tag}>: no sobrevive al storage format")

    return problems


def slugify(text: str) -> str:
    """Ancla estable para un encabezado: sin acentos, minúsculas, guiones."""
    plain = unicodedata.normalize("NFKD", text).encode("ascii", "ignore").decode()
    plain = re.sub(r"`|\*\*?", "", plain)
    return re.sub(r"[^a-z0-9]+", "-", plain.lower()).strip("-")


def anchors_in(body: str, modern: bool = True) -> set:
    """Anclas a las que se puede enlazar en una página.

    Cada `# Sección` tiene la suya automática (`#soporte`, `#configuracion`), y cualquier
    encabezado puede declarar una explícita con `{#adr-064}`. Son exactamente las que
    `md_to_storage` convierte en macros `anchor`, así que validar contra este conjunto es
    validar contra lo que existirá en Confluence.
    """
    prose = re.sub(r"```.*?```", "", body, flags=re.DOTALL)
    found = {slugify(h) for h in headings(body, 1)} if modern else set()
    found |= set(re.findall(r"^#{1,6} .*\{#([A-Za-z0-9_-]+)\}\s*$", prose, re.M))
    return found


def validate_anchors(body: str, page_path: Path, docs_root: Path, anchors_by_relpath: dict) -> list:
    """Enlaces con `#ancla` que no llevarían a ningún sitio. Vacío = correcto.

    Va aparte de `validate_body` porque necesita las anclas de *todas* las páginas.
    """
    problems = []
    own = os.path.relpath(page_path, docs_root).replace(os.sep, "/")
    for m in re.finditer(r"\]\(([^)#\s]*)#([^)\s]+)\)", body):
        target, anchor = m.group(1), m.group(2)
        if target and not target.endswith(".md"):
            continue
        rel = own if not target else \
            os.path.relpath((page_path.parent / target).resolve(), docs_root).replace(os.sep, "/")
        if rel in anchors_by_relpath and anchor not in anchors_by_relpath[rel]:
            problems.append(f"ancla inexistente -> {target or '(esta página)'}#{anchor}")
    return problems


def labels_from(meta: dict) -> list:
    """Etiquetas de Confluence derivadas del front matter.

    `tags` tal cual, más `type` y `status` con prefijo para que se puedan filtrar por
    separado. Las páginas de funcionalidad llevan además `funcionalidad` y `soporte`,
    que es lo que permite a soporte buscar con `label = soporte`. Confluence solo
    acepta etiquetas sin espacios ni mayúsculas.
    """
    raw = list(meta.get("tags") or [])
    raw += [f"{key}-{meta[key]}" for key in ("type", "status") if meta.get(key)]
    if meta.get("type") == "feature":
        raw += list(FEATURE_LABELS)
    out, seen = [], set()
    for item in raw:
        slug = re.sub(r"[^a-z0-9-]+", "-", str(item).lower()).strip("-")
        if slug and slug not in seen:
            seen.add(slug)
            out.append(slug)
    return out


# ── estado de sincronización ─────────────────────────────────────────────────

# Content property donde el publicador sella lo que dejó en cada página. Vive en la
# página, no en el repositorio: así el estado es el mismo para todo el que publique,
# sin un archivo de estado que se pelee en cada merge.
SYNC_PROPERTY_KEY = "msClpBlpSync"

# Estados posibles de una página, y lo que significan al leer el reporte.
NUEVA = "nueva"                 # no existe en Confluence
ACTUALIZAR = "actualizar"       # el repo cambió y nadie la editó a mano
SIN_CAMBIOS = "sin-cambios"     # idéntica a lo último que publicamos: no se toca
CONSERVADA = "conservada"       # la editó una persona; el repo no cambió desde entonces
CONFLICTO = "conflicto"         # la editó una persona Y el repo también cambió

ESCRIBEN = (NUEVA, ACTUALIZAR)


def fingerprint(title: str, parent_title, labels: list, body: str) -> str:
    """Huella de lo que este publicador generaría para una página.

    Se compara contra la huella sellada en la corrida anterior para saber si el repo
    cambió. Es hash de **nuestra propia salida**, nunca del cuerpo que devuelve
    Confluence: ese pasa por una conversión a ADF y vuelve normalizado, así que
    compararlo daría deriva falsa en cada corrida.

    Entran el padre y las etiquetas además del cuerpo porque también son cosas que la
    publicación escribe: sin ellos, mover una página en el manifiesto o cambiarle los
    `tags` no produciría ninguna escritura y el cambio se quedaría en el repo.
    """
    material = "\n".join([title, parent_title or "", ",".join(labels), body])
    return hashlib.sha256(material.encode()).hexdigest()


def classify(existing, prop, page_hash: str, forced: bool) -> str:
    """Qué hacer con una página, según lo que hay en Confluence y lo que sellamos.

    La regla que gobierna todo: **si la versión remota subió por encima de la que
    sellamos, alguien la editó** — Confluence incrementa `version.number` en toda
    edición — y entonces gana Confluence. `--force` es la única forma de que gane el
    repo, y aun así nada se pierde: el historial de versiones conserva lo pisado.
    """
    if existing is None:
        return NUEVA
    if forced:
        return ACTUALIZAR
    if not prop:
        # Página publicada antes de que existiera el sellado. No hay forma de saber si
        # alguien la editó, así que se mantiene el comportamiento histórico (gana el
        # repo) y a partir de esta corrida queda sellada y protegida.
        return ACTUALIZAR

    edited = remote_version(existing) > int(prop.get("version") or 0)
    # `adoptado` marca las páginas que --adopt entregó a Confluence: son suyas desde
    # entonces, aunque nadie las haya vuelto a tocar todavía.
    if edited or prop.get("origin") == "adoptado":
        return CONSERVADA if prop.get("sourceHash") == page_hash else CONFLICTO
    return SIN_CAMBIOS if prop.get("sourceHash") == page_hash else ACTUALIZAR


def remote_version(page: dict) -> int:
    """Número de versión de una página tal como lo devuelve la API.

    Sin versión no hay detección de ediciones, así que aquí no hay valor por defecto:
    un `1` supuesto convertiría la edición de una persona en «no editada» y la pisaría.
    """
    number = (page.get("version") or {}).get("number")
    if not isinstance(number, int):
        raise SystemExit(f"La API no devolvió la versión de la página {page.get('id')} "
                         f"('{page.get('title')}'); sin ella no se puede saber si alguien "
                         f"la editó. Vuelve a intentarlo.")
    return number


def sync_payload(doc_id: str, version: int, page_hash: str, email: str, origin: str) -> dict:
    """Contenido de la content property que se sella tras escribir una página."""
    return {
        "docId": doc_id,
        "version": version,
        "sourceHash": page_hash,
        "publishedAt": datetime.datetime.now(datetime.timezone.utc)
                               .strftime("%Y-%m-%dT%H:%M:%SZ"),
        "publishedBy": email,
        "origin": origin,
    }


# ── manifiesto ───────────────────────────────────────────────────────────────


def load_pages(manifest_path: Path):
    """Aplana el árbol del manifiesto a una lista en orden padre→hijo.

    Cada entrada lleva su título, su archivo y el título de su padre, que es lo
    único que el publicador necesita para reconstruir la jerarquía.
    """
    data = yaml.safe_load(manifest_path.read_text())
    root = manifest_path.parent
    flat = []

    def walk(nodes, parent_title, depth):
        for node in nodes or []:
            title = node["title"]
            flat.append({
                "title": title,
                # El título del manifiesto antes de aplicarle el prefijo: es contra este
                # que se valida el front matter, que no sabe nada de prefijos.
                "raw_title": title,
                "path": root / node["file"],
                "parent": parent_title,
                "depth": depth,
            })
            walk(node.get("children"), title, depth + 1)

    walk(data.get("pages"), None, 0)
    return data, flat


# ── markdown → storage format ────────────────────────────────────────────────


def collapse_source_newlines(html: str) -> str:
    """Une las líneas que el repo parte por ancho, para que no se vean como saltos duros.

    Las páginas se escriben con las frases envueltas a ~100 columnas, y en Markdown
    esas líneas son un solo párrafo. Pero Confluence Cloud, al convertir el storage
    format a su editor, trata cada `\\n` dentro de un bloque de texto como un salto de
    línea real: el resultado es un párrafo cortado exactamente por donde el fichero
    hacía el wrap. Colapsarlos aquí es lo que hace que el texto fluya según el ancho de
    la ventana, que es lo que un párrafo debe hacer.

    Se preserva lo único donde un salto sí significa algo: los bloques `<pre>` (código
    indentado con 4 espacios). Los bloques con fence ya salieron del HTML como marcador
    antes de esta función, así que su contenido ni se toca.
    """
    protected: list[str] = []

    def stash_pre(m):
        protected.append(m.group(0))
        return f"CONFLUENCEPRE{len(protected) - 1}ENDPRE"

    html = re.sub(r"<pre>.*?</pre>", stash_pre, html, flags=re.DOTALL)
    html = re.sub(r"[ \t]*\n[ \t]*", " ", html)
    return re.sub(r"CONFLUENCEPRE(\d+)ENDPRE", lambda m: protected[int(m.group(1))], html)


def parse_fence_info(info: str):
    """`json collapse title="Respuesta completa"` → ('json', True, 'Respuesta completa').

    La primera palabra es el lenguaje, como en cualquier fence; `collapse` y `title="…"`
    son opcionales y se traducen a los parámetros de la macro `code`.
    """
    title = None
    m = re.search(r'title="([^"]*)"', info)
    if m:
        title = m.group(1)
        info = info[:m.start()] + info[m.end():]
    words = info.split()
    collapse = "collapse" in words
    words = [w for w in words if w != "collapse"]
    return (words[0].lower() if words else ""), collapse, title


def diagram_filename(source: str) -> str:
    """Nombre del adjunto de un diagrama: depende solo de su fuente.

    Así el cuerpo de la página (y su huella) es el mismo sin necesidad de renderizar,
    y un diagrama que no cambió nunca vuelve a subirse con otro nombre.
    """
    return f"diagrama-{hashlib.sha256(source.encode()).hexdigest()[:12]}.png"


def _macro(name: str, body: str, params: dict | None = None, plain: bool = False) -> str:
    out = f'<ac:structured-macro ac:name="{name}">'
    for key, value in (params or {}).items():
        out += f'<ac:parameter ac:name="{key}">{value}</ac:parameter>'
    if plain:
        out += f'<ac:plain-text-body><![CDATA[{body}]]></ac:plain-text-body>'
    elif body:
        out += f'<ac:rich-text-body>{body}</ac:rich-text-body>'
    return out + '</ac:structured-macro>'


def anchor_macro(name: str) -> str:
    return f'<ac:structured-macro ac:name="anchor"><ac:parameter ac:name="">{name}</ac:parameter></ac:structured-macro>'


def header_storage(meta: dict, body_md: str) -> str:
    """Cabecera que el publicador antepone a una página con `summary`.

    Sustituye al preámbulo Objetivo/Alcance/Audiencia: el lector ve en un panel qué es la
    página y para quién, y debajo el índice, sin gastar una pantalla en ello. Una página
    en borrador u obsoleta lo dice en un panel de aviso, antes que nada.
    """
    out = ""
    status = meta.get("status")
    if status == "deprecated":
        out += _macro("warning", "<p><strong>Página obsoleta.</strong> Se conserva como "
                                 "historial; no describe el comportamiento actual.</p>")
    elif status == "draft":
        out += _macro("warning", "<p><strong>Borrador.</strong> Puede no coincidir todavía "
                                 "con el código.</p>")
    audience = ", ".join(meta.get("audience") or [])
    facts = " · ".join(x for x in (
        f"Para: {audience}" if audience else "",
        f"Revisado: {meta.get('lastReviewed')}" if meta.get("lastReviewed") else "",
    ) if x)
    summary = md.markdown(str(meta["summary"]).strip())
    out += _macro("info", summary + (f"<p><em>{facts}</em></p>" if facts else ""))
    if len(headings(body_md, 1)) + len(headings(body_md, 2)) >= 4:
        out += _macro("toc", "", {"maxLevel": "2", "minLevel": "1"})
    return out


def md_to_storage(text: str, titles_by_relpath: dict, page_dir: Path, docs_root: Path,
                  mermaid_macro: str, anchors_by_relpath: dict | None = None,
                  modern: bool = False, own_rel: str | None = None) -> str:
    """Convierte Markdown a storage format de Confluence.

    Lo que más importa: los bloques ```mermaid``` se vuelven imagen adjunta (o su fuente
    plegada), el resto de bloques de código macros `code`, los avisos `> [!WARNING]`
    paneles, y los enlaces relativos entre páginas enlaces nativos por título —con su
    `#ancla` cuando la página destino la declara—; si se dejaran como href a un .md
    quedarían rotos en Confluence.

    `modern` es una página con `summary`: sus secciones `#` reciben ancla automática. Las
    páginas del formato antiguo no, para no cambiarles el cuerpo sin motivo.
    """
    anchors_by_relpath = anchors_by_relpath or {}
    # Los fences se extraen ANTES de convertir a HTML: el conversor escaparía el
    # contenido y ya no sería recuperable de forma fiable.
    blocks: list[tuple[str, str]] = []

    def stash_fence(m):
        blocks.append((m.group(1) or "", m.group(2)))
        return f"\n\nCONFLUENCEFENCE{len(blocks) - 1}ENDFENCE\n\n"

    text = re.sub(r"```([^\n`]*)\n(.*?)```", stash_fence, text, flags=re.DOTALL)

    html = md.markdown(text, extensions=MD_EXTENSIONS)
    html = collapse_source_newlines(html)

    def restore_fence(m):
        info, body = blocks[int(m.group(1))]
        lang, collapse, title = parse_fence_info(info)
        if lang == "mermaid":
            source = _macro("code", body, {"title": "Fuente del diagrama (mermaid)",
                                           "collapse": "true"}, plain=True)
            if mermaid_macro == "image":
                # El publicador renderiza la fuente a PNG y la sube como adjunto de la
                # página (ver render_diagram); aquí solo se referencia por nombre. La
                # fuente queda debajo, plegada, para quien quiera editarla.
                return (f'<ac:image ac:align="center"><ri:attachment '
                        f'ri:filename="{diagram_filename(body)}"/></ac:image>' + source)
            if mermaid_macro == "code":
                # Sin render: Confluence Cloud no dibuja mermaid y las apps que lo hacen
                # son de paga, así que viaja solo la fuente, plegada y titulada.
                return source
            return _macro(mermaid_macro, body, plain=True)
        params = {}
        if lang:
            params["language"] = lang
        if title:
            params["title"] = title
        if collapse:
            params["collapse"] = "true"
        return _macro("code", body, params, plain=True)

    html = re.sub(r"<p>CONFLUENCEFENCE(\d+)ENDFENCE</p>", restore_fence, html)
    html = re.sub(r"CONFLUENCEFENCE(\d+)ENDFENCE", restore_fence, html)

    # Avisos GFM. Python-Markdown los deja como cita cuyo primer párrafo empieza por
    # `[!TIPO]`; se cambian por el panel equivalente de Confluence.
    def rewrite_alert(m):
        kind, rest = m.group(1), m.group(2).strip()
        rest = re.sub(r"^<p>\s*</p>", "", "<p>" + rest)
        return _macro(ALERT_MACROS[kind], rest)

    html = re.sub(r"<blockquote>\s*<p>\[!(" + "|".join(ALERT_MACROS) + r")\]\s*(.*?)</blockquote>",
                  rewrite_alert, html, flags=re.DOTALL)

    # Anclas: `{#id}` explícito en cualquier encabezado, y automático en los `#` de las
    # páginas modernas. Confluence solo enlaza con fiabilidad a una macro `anchor`.
    def rewrite_heading(m):
        level, attrs, inner = m.group(1), m.group(2) or "", m.group(3)
        names = []
        explicit = re.search(r'id="([^"]+)"', attrs)
        if explicit:
            names.append(explicit.group(1))
        if modern and level == "1":
            auto = slugify(html_lib.unescape(re.sub(r"<[^>]+>", "", inner)))
            if auto not in names:
                names.append(auto)
        return f"<h{level}>{''.join(anchor_macro(n) for n in names)}{inner}</h{level}>"

    html = re.sub(r"<h([1-6])((?:\s+[a-z-]+=\"[^\"]*\")*)>(.*?)</h\1>", rewrite_heading, html,
                  flags=re.DOTALL)

    def link(title, label, anchor=None):
        page = f'<ri:page ri:content-title="{title}"/>' if title else ""
        attr = f' ac:anchor="{anchor}"' if anchor else ""
        return (f'<ac:link{attr}>{page}'
                f'<ac:plain-text-link-body><![CDATA[{label}]]></ac:plain-text-link-body></ac:link>')

    def rewrite_link(m):
        href, label = m.group(1), m.group(2)
        if href.startswith(("http://", "https://", "mailto:")):
            return m.group(0)
        target, _, anchor = href.partition("#")
        if not target:
            # Enlace a una sección de la misma página.
            known = modern or anchor in anchors_by_relpath.get(own_rel, ())
            return link(None, label, anchor) if anchor and known else m.group(0)
        if not target.endswith(".md"):
            return m.group(0)
        rel = os.path.relpath((page_dir / target).resolve(), docs_root).replace(os.sep, "/")
        title = titles_by_relpath.get(rel)
        if not title:
            # Apunta fuera del árbol publicado (p. ej. docs/parity/). Se degrada a
            # texto plano en vez de dejar un enlace que Confluence no resolvería.
            return label
        # El ancla solo viaja si la página destino la declara; las del formato antiguo
        # usan anclas de estilo GitHub que en Confluence no existen.
        keep = anchor if anchor and anchor in anchors_by_relpath.get(rel, ()) else None
        return link(title, label, keep)

    return re.sub(r'<a href="([^"]+)">(.*?)</a>', rewrite_link, html, flags=re.DOTALL)


def mermaid_sources(body_md: str) -> list:
    """Fuentes de los diagramas de una página, en orden de aparición."""
    return [m.group(2) for m in re.finditer(r"```([^\n`]*)\n(.*?)```", body_md, flags=re.DOTALL)
            if parse_fence_info(m.group(1) or "")[0] == "mermaid"]


def render_diagram(source: str) -> Path:
    """Renderiza una fuente mermaid a PNG, con caché por contenido.

    Usa mermaid-cli vía npx (versión fija en MERMAID_CLI): la primera vez descarga el
    paquete y un Chromium, después cada diagrama tarda un par de segundos y uno ya
    renderizado no se vuelve a renderizar nunca. Escala 2 para que se lea nítido.
    """
    out = MERMAID_CACHE / diagram_filename(source)
    if out.exists():
        return out
    MERMAID_CACHE.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        src = Path(tmp) / "diagrama.mmd"
        src.write_text(source)
        tmp_out = Path(tmp) / out.name
        res = subprocess.run(["npx", "-y", MERMAID_CLI, "-i", str(src), "-o", str(tmp_out),
                              "-b", "white", "-s", "2", "-q"],
                             capture_output=True, text=True, timeout=600)
        if res.returncode != 0 or not tmp_out.exists():
            raise RuntimeError((res.stderr or res.stdout or "sin salida").strip()[-600:])
        shutil.move(str(tmp_out), out)
    return out


# ── cliente REST ─────────────────────────────────────────────────────────────

# Confluence Cloud corta conexiones TLS de vez en cuando (`ConnectionResetError:
# [Errno 54]` durante el handshake) y responde 429 bajo ráfaga — este script hace
# ~140 llamadas seguidas. Sin reintento, un único corte aborta la corrida entera;
# si ocurre en mitad de la escritura, deja el árbol publicado a medias.
RETRY_ATTEMPTS = 4
RETRY_BASE_DELAY = 1.0
TIMEOUT_SECONDS = 30
MAX_RETRY_AFTER = 60.0


def _retry_delay(attempt: int, retry_after: str | None) -> float:
    """Backoff exponencial con jitter, o lo que pida `Retry-After` si es un número de segundos."""
    if retry_after:
        try:
            return min(float(retry_after), MAX_RETRY_AFTER)
        except ValueError:
            pass  # también admite fecha HTTP; para eso basta el backoff normal
    return RETRY_BASE_DELAY * (2 ** attempt) + random.uniform(0, 0.5)


def _reason(exc: Exception) -> str:
    return f"{type(exc).__name__}: {getattr(exc, 'reason', None) or exc}"


class Confluence:
    def __init__(self, base_url: str, email: str, token: str):
        self.base = base_url.rstrip("/")
        cred = base64.b64encode(f"{email}:{token}".encode()).decode()
        self._users: dict = {}
        self.headers = {
            "Authorization": f"Basic {cred}",
            "Content-Type": "application/json",
            "Accept": "application/json",
        }

    def _call(self, method: str, path: str, payload=None, soft: bool = False,
              raw: bytes | None = None, content_type: str | None = None):
        """Llama a la API reintentando los fallos transitorios.

        Qué se reintenta y qué no, por seguridad de los datos: un fallo de conexión
        (TLS cortado, timeout, DNS) prueba que la petición no llegó a procesarse, así
        que repetirla es seguro para cualquier método, POST incluido. Un `429` también:
        la petición fue rechazada, no procesada. Un `5xx` en cambio es ambiguo — el
        servidor pudo haber creado la página antes de fallar —, así que solo se
        reintenta en métodos idempotentes; repetir un POST ahí duplicaría la página.
        """
        url = f"{self.base}{path}"
        data = raw if raw is not None else (json.dumps(payload).encode() if payload is not None else None)
        headers = dict(self.headers)
        if content_type:
            # Subida de adjuntos: multipart, y la API exige este header contra XSRF.
            headers["Content-Type"] = content_type
            headers["X-Atlassian-Token"] = "no-check"
        idempotent = method != "POST"

        for attempt in range(RETRY_ATTEMPTS):
            last_attempt = attempt == RETRY_ATTEMPTS - 1
            req = urllib.request.Request(url, data=data, headers=headers, method=method)
            try:
                with urllib.request.urlopen(req, timeout=TIMEOUT_SECONDS) as resp:
                    return json.loads(resp.read() or "{}")
            except urllib.error.HTTPError as e:
                if not last_attempt and (e.code == 429 or (e.code >= 500 and idempotent)):
                    delay = _retry_delay(attempt, e.headers.get("Retry-After"))
                    print(f"    (reintento {attempt + 1}/{RETRY_ATTEMPTS - 1}) "
                          f"{method} {path} -> HTTP {e.code}; espero {delay:.1f}s")
                    time.sleep(delay)
                    continue
                body = e.read().decode(errors="replace")[:800]
                if soft:
                    print(f"    (aviso) {method} {path} -> HTTP {e.code}")
                    return None
                raise SystemExit(f"\n{method} {path} -> HTTP {e.code}\n{body}\n")
            except (urllib.error.URLError, http.client.HTTPException, OSError) as e:
                # HTTPError ya se capturó arriba; aquí solo quedan cortes de conexión.
                if not last_attempt:
                    delay = _retry_delay(attempt, None)
                    print(f"    (reintento {attempt + 1}/{RETRY_ATTEMPTS - 1}) "
                          f"{method} {path} -> {_reason(e)}; espero {delay:.1f}s")
                    time.sleep(delay)
                    continue
                if soft:
                    print(f"    (aviso) {method} {path} -> {_reason(e)}")
                    return None
                raise SystemExit(
                    f"\n{method} {path} -> {_reason(e)}\n"
                    f"La red falló en los {RETRY_ATTEMPTS} intentos. Vuelve a lanzar el comando: "
                    f"la publicación es idempotente y las páginas ya escritas se actualizan.\n")
        return None  # inalcanzable: el último intento devuelve o lanza

    def space_id(self, key: str) -> str:
        res = self._call("GET", f"/wiki/api/v2/spaces?keys={urllib.parse.quote(key)}")
        results = res.get("results") or []
        if not results:
            raise SystemExit(f"No existe el espacio '{key}', o el token no tiene acceso.")
        return results[0]["id"]

    def find_page(self, space_id: str, title: str):
        q = urllib.parse.quote(title)
        res = self._call("GET", f"/wiki/api/v2/spaces/{space_id}/pages?title={q}&limit=250")
        for page in res.get("results") or []:
            if page["title"] == title:
                return page
        return None

    def ancestor_ids(self, page_id: str) -> set:
        """Ids de todos los ancestros de una página, para saber si vive bajo nuestro padre."""
        res = self._call("GET", f"/wiki/api/v2/pages/{page_id}/ancestors?limit=100")
        return {str(a["id"]) for a in res.get("results") or []}

    def create(self, space_id: str, title: str, body: str, parent_id):
        payload = {
            "spaceId": space_id,
            "status": "current",
            "title": title,
            "body": {"representation": "storage", "value": body},
        }
        if parent_id:
            payload["parentId"] = parent_id
        return self._call("POST", "/wiki/api/v2/pages", payload)

    def update(self, page_id: str, title: str, body: str, version: int, parent_id):
        payload = {
            "id": page_id,
            "status": "current",
            "title": title,
            "body": {"representation": "storage", "value": body},
            "version": {"number": version + 1, "message": "Sincronizado desde el repositorio"},
        }
        if parent_id:
            payload["parentId"] = parent_id
        return self._call("PUT", f"/wiki/api/v2/pages/{page_id}", payload)

    def get_property(self, page_id: str, key: str):
        """La content property del publicador, o None si la página no la tiene todavía.

        Devuelve `(id, version, value)`: para reescribirla hace falta su propio id y su
        propio número de versión, que no tienen nada que ver con los de la página.
        """
        res = self._call("GET", f"/wiki/api/v2/pages/{page_id}/properties"
                                f"?key={urllib.parse.quote(key)}", soft=True)
        for prop in (res or {}).get("results") or []:
            if prop.get("key") == key:
                return prop
        return None

    def set_property(self, page_id: str, key: str, value: dict, existing=None):
        """Sella el estado de sincronización en la página.

        Es accesorio respecto a publicar —igual que las etiquetas—, así que un fallo
        avisa y sigue: una property que no se pudo escribir se traduce en que la
        próxima corrida trate la página como no sellada, no en un árbol a medio subir.
        """
        if existing:
            payload = {
                "key": key,
                "value": value,
                "version": {"number": (existing.get("version") or {}).get("number", 1) + 1},
            }
            self._call("PUT", f"/wiki/api/v2/pages/{page_id}/properties/{existing['id']}",
                       payload, soft=True)
        else:
            self._call("POST", f"/wiki/api/v2/pages/{page_id}/properties",
                       {"key": key, "value": value}, soft=True)

    def user_name(self, account_id):
        """Nombre de quien editó una página. Sin él el reporte solo tendría un accountId."""
        if not account_id:
            return "desconocido"
        if account_id not in self._users:
            res = self._call("GET", f"/wiki/rest/api/user?accountId="
                                    f"{urllib.parse.quote(account_id)}", soft=True)
            self._users[account_id] = (res or {}).get("displayName") or account_id
        return self._users[account_id]

    def page(self, page_id: str) -> dict:
        """La página tal como está ahora, para leer su versión después de subir adjuntos."""
        return self._call("GET", f"/wiki/api/v2/pages/{page_id}")

    def upload_attachment(self, page_id: str, path: Path) -> bool:
        """Sube (o reemplaza) un adjunto con el nombre del archivo. Devuelve si se pudo.

        `PUT` sobre `child/attachment` crea o actualiza por nombre, así que repetirlo es
        seguro y entra en el reintento de métodos idempotentes.
        """
        boundary = f"----msclpblp{random.getrandbits(64):x}"
        body = (f"--{boundary}\r\n"
                f'Content-Disposition: form-data; name="file"; filename="{path.name}"\r\n'
                f"Content-Type: image/png\r\n\r\n").encode() + path.read_bytes() + \
               (f"\r\n--{boundary}\r\n"
                f'Content-Disposition: form-data; name="minorEdit"\r\n\r\ntrue'
                f"\r\n--{boundary}--\r\n").encode()
        res = self._call("PUT", f"/wiki/rest/api/content/{page_id}/child/attachment", soft=True,
                         raw=body, content_type=f"multipart/form-data; boundary={boundary}")
        return res is not None

    def pages_under(self, space_key: str, root_id: str | None) -> list:
        """Todas las páginas del árbol (o del espacio, sin padre), con sus ancestros."""
        cql = f"type = page AND ancestor = {root_id}" if root_id \
            else f'type = page AND space = "{space_key}"'
        out, start = [], 0
        while True:
            res = self._call("GET", "/wiki/rest/api/content/search?cql="
                             f"{urllib.parse.quote(cql)}&expand=ancestors,version"
                             f"&limit=100&start={start}")
            batch = res.get("results") or []
            out += batch
            if len(batch) < 100:
                return out
            start += len(batch)

    def move_under(self, page_id: str, target_id: str):
        """Mueve la página (con todo su subárbol) como última hija de `target_id`."""
        return self._call("PUT", f"/wiki/rest/api/content/{page_id}/move/append/{target_id}")

    def add_labels(self, page_id: str, labels: list):
        """Aplica las etiquetas del front matter. Solo añade: nunca quita las existentes.

        Es accesorio respecto a publicar, así que un fallo aquí avisa y sigue en vez de
        abortar con el árbol a medio subir. La v2 no expone escritura de etiquetas.
        """
        if not labels:
            return
        payload = [{"prefix": "global", "name": name} for name in labels]
        self._call("POST", f"/wiki/rest/api/content/{page_id}/label", payload, soft=True)


# ── main ─────────────────────────────────────────────────────────────────────


def main():
    ap = argparse.ArgumentParser(description="Publica la base de conocimiento en Confluence.")
    ap.add_argument("--space", help="Clave del espacio. Por defecto, la del manifiesto.")
    ap.add_argument("--manifest", type=Path, default=MANIFEST)
    ap.add_argument("--mermaid-macro", default="image",
                    help="Cómo publicar los diagramas. 'image' (por defecto) los renderiza a PNG con "
                         "mermaid-cli (npx) y los adjunta a la página; 'code' publica solo la fuente "
                         "plegada; cualquier otro valor es el nombre de una macro de Mermaid que el "
                         "sitio tenga instalada.")
    ap.add_argument("--parent-id",
                    help="Id de la página o carpeta bajo la que colgar el árbol. Se saca de la URL "
                         "en Confluence. Sin esto, el árbol cuelga de la raíz del espacio.")
    ap.add_argument("--title-prefix", default="",
                    help="Prefijo para todos los títulos, p. ej. 'ms-clp-blp · '. Los títulos son únicos "
                         "por espacio: úsalo si el espacio ya tiene páginas con nombres genéricos.")
    ap.add_argument("--allow-overwrite", action="store_true",
                    help="Permite sobrescribir páginas existentes fuera del padre indicado. "
                         "Por defecto el script aborta antes de escribir nada.")
    ap.add_argument("--strict", action="store_true",
                    help="Aborta si alguna página tiene el front matter incompleto. "
                         "Pensado para CI; en local basta con el aviso.")
    ap.add_argument("--dry-run", action="store_true",
                    help="No escribe nada y no toca la red: valida el manifiesto y las páginas.")
    ap.add_argument("--status", action="store_true",
                    help="Consulta Confluence y muestra en qué estado está cada página "
                         "(nueva, sin cambios, actualizar, conservada, conflicto). No escribe nada.")
    ap.add_argument("--force", action="store_true",
                    help="Publica también las páginas editadas en Confluence, pisándolas. "
                         "Lo pisado sigue en el historial de versiones de la página.")
    ap.add_argument("--force-page", action="append", default=[], metavar="TÍTULO",
                    help="Como --force pero solo para ese título. Se puede repetir.")
    ap.add_argument("--adopt", action="store_true",
                    help="Entrega a Confluence las páginas que ya existen: sella su estado actual "
                         "sin escribir ningún cuerpo. A partir de ahí el publicador solo avisa "
                         "cuando el repo cambia, y nunca las pisa sin --force.")
    ap.add_argument("--archive-orphans", action="store_true",
                    help=f"Mueve bajo '{ARCHIVE_TITLE}' las páginas que este publicador creó y que ya "
                         "no están en el manifiesto. Con --status solo las lista.")
    args = ap.parse_args()

    manifest, pages = load_pages(args.manifest)
    docs_root = args.manifest.parent
    space_key = args.space or manifest.get("space")
    if not space_key:
        raise SystemExit("Indica el espacio con --space o en el manifiesto.")

    # El prefijo se aplica una sola vez, aquí: a partir de este punto `title` es el
    # título real en Confluence, así que la búsqueda, la creación y los enlaces
    # internos quedan consistentes sin propagarlo por el resto del código.
    if args.title_prefix:
        for p in pages:
            p["title"] = f"{args.title_prefix}{p['title']}"
            # También al padre: es la clave con la que cada hija busca el id de su padre
            # en el mapa de la publicación, y ese mapa está indexado por título real.
            if p["parent"]:
                p["parent"] = f"{args.title_prefix}{p['parent']}"

    titles_by_relpath = {}
    for p in pages:
        rel = os.path.relpath(p["path"].resolve(), docs_root).replace(os.sep, "/")
        titles_by_relpath[rel] = p["title"]

    missing = [str(p["path"]) for p in pages if not p["path"].exists()]
    if missing:
        raise SystemExit("Faltan archivos declarados en el manifiesto:\n  " + "\n  ".join(missing))

    # El front matter se lee una sola vez y se guarda junto a la página: lo necesitan la
    # validación, el cuerpo (hay que quitarlo antes de convertir) y las etiquetas.
    declared_files = set(titles_by_relpath)
    for p in pages:
        p["meta"], p["body_md"] = split_frontmatter(p["path"].read_text())
        p["rel"] = os.path.relpath(p["path"].resolve(), docs_root.resolve()).replace(os.sep, "/")
        # Una página con `summary` es del formato nuevo: cabecera generada, anclas
        # automáticas en sus secciones y macro `children` si tiene hijas.
        p["modern"] = bool(p["meta"].get("summary"))
        p["has_children"] = any(c["parent"] == p["title"] for c in pages)

    # Una página del formato antiguo solo aporta sus anclas explícitas `{#id}`: las suyas de
    # estilo GitHub (`#mongodb`) no existen en Confluence y no deben validarse ni emitirse.
    anchors_by_relpath = {p["rel"]: anchors_in(p["body_md"], p["modern"]) for p in pages}
    anchors_by_relpath = {rel: a for rel, a in anchors_by_relpath.items() if a}

    for p in pages:
        p["problems"] = (validate_meta(p["meta"], p["raw_title"])
                         + validate_body(p["body_md"], p["path"].resolve(),
                                         docs_root.resolve(), declared_files, p["meta"])
                         + (validate_anchors(p["body_md"], p["path"].resolve(),
                                             docs_root.resolve(), anchors_by_relpath)
                            if p["modern"] else []))
        lines = p["body_md"].count("\n")
        p["warnings"] = [f"{lines} líneas; una página de funcionalidad debería leerse de una "
                         f"sentada (≤ {FEATURE_MAX_LINES})"] \
            if p["meta"].get("type") == "feature" and lines > FEATURE_MAX_LINES else []

    bad = [p for p in pages if p["problems"]]

    # El cuerpo se genera antes de hablar con Confluence porque su huella es lo que
    # decide si hay algo que publicar: sin ella no se puede distinguir «el repo cambió»
    # de «alguien editó la página».
    for p in pages:
        p["labels"] = labels_from(p["meta"])
        p["diagrams"] = mermaid_sources(p["body_md"]) if args.mermaid_macro == "image" else []
        body = md_to_storage(p["body_md"], titles_by_relpath, p["path"].parent, docs_root,
                             args.mermaid_macro, anchors_by_relpath, p["modern"], p["rel"])
        if p["modern"]:
            body = header_storage(p["meta"], p["body_md"]) + body
            if p["has_children"]:
                body += _macro("children", "", {"all": "false"})
        p["body"] = body
        p["hash"] = fingerprint(p["title"], p["parent"], p["labels"], p["body"])

    print(f"{len(pages)} páginas · espacio {space_key} · macro de diagramas '{args.mermaid_macro}'")

    if args.dry_run:
        for p in pages:
            indent = "   " * p["depth"] + ("└─ " if p["depth"] else "")
            mark = "!" if p["problems"] else " "
            print(f" {mark} {indent}{p['title']}  ({p['path'].name})")
        if bad:
            print(f"\nProblemas en {len(bad)} de {len(pages)} páginas "
                  f"(front matter, estructura, enlaces, anclas o HTML crudo):")
            for p in bad:
                rel = os.path.relpath(p["path"], docs_root)
                print(f"  {rel}: {', '.join(p['problems'])}")
            print("Contrato: .claude/skills/confluence-docs/SKILL.md")
        warned = [p for p in pages if p["warnings"]]
        if warned:
            print(f"\nAvisos (no fallan) en {len(warned)} páginas:")
            for p in warned:
                print(f"  {p['rel']}: {', '.join(p['warnings'])}")
        print("\nDry run: no se escribió nada.")
        return

    if bad:
        rels = ", ".join(os.path.relpath(p["path"], docs_root) for p in bad[:5])
        detail = f"{rels}{' …' if len(bad) > 5 else ''}"
        if args.strict:
            raise SystemExit(f"--strict: {len(bad)} páginas con problemas: {detail}\n"
                             "Corre con --dry-run para el detalle por archivo.")
        print(f"Aviso: {len(bad)} páginas con problemas ({detail}). "
              "Se publican igual; --dry-run da el detalle.")

    base = os.environ.get("CONFLUENCE_BASE_URL")
    email = os.environ.get("CONFLUENCE_EMAIL")
    token = os.environ.get("CONFLUENCE_API_TOKEN")
    if not all([base, email, token]):
        raise SystemExit("Faltan CONFLUENCE_BASE_URL, CONFLUENCE_EMAIL o CONFLUENCE_API_TOKEN.")

    api = Confluence(base, email, token)
    space_id = api.space_id(space_key)
    root_parent = str(args.parent_id) if args.parent_id else None

    def page_url(page_id) -> str:
        return f"{base.rstrip('/')}/wiki/pages/viewpage.action?pageId={page_id}"

    forced_titles = {t.strip().lower() for t in args.force_page}

    # ── archivar lo que salió del manifiesto ─────────────────────────────────
    if args.archive_orphans:
        archive_orphans(api, space_id, space_key, root_parent, pages, args, page_url)
        return

    # ── verificación previa ──────────────────────────────────────────────────
    # Se resuelve el destino de todas las páginas ANTES de escribir ninguna: un árbol
    # publicado a medias es peor que uno no publicado. Una página que ya existe
    # fuera de nuestro padre es contenido ajeno y abortamos en lugar de pisarlo.
    # Aquí también se decide, página por página, quién gana: el repo o Confluence.
    plan, collisions = [], []
    for p in pages:
        existing = api.find_page(space_id, p["title"])
        if not existing:
            plan.append({"page": p, "existing": None, "prop": None, "state": NUEVA})
            continue
        ours = True
        if root_parent:
            ours = root_parent in api.ancestor_ids(existing["id"])
        if not (ours or args.allow_overwrite):
            collisions.append((p["title"], existing["id"]))
            continue
        prop = api.get_property(existing["id"], SYNC_PROPERTY_KEY)
        forced = args.force or p["title"].lower() in forced_titles \
            or p["raw_title"].lower() in forced_titles
        state = classify(existing, (prop or {}).get("value"), p["hash"], forced)
        plan.append({"page": p, "existing": existing, "prop": prop, "state": state})

    if collisions:
        print("\nAbortado: estas páginas ya existen en el espacio, fuera del padre indicado.")
        print("Son contenido ajeno y el script no las va a sobrescribir.\n")
        for title, pid in collisions:
            print(f"  {title}\n    {page_url(pid)}")
        print("\nOpciones:")
        print("  1. Usa --title-prefix 'ms-clp-blp · ' para separar los títulos.")
        print("  2. Renombra el 'title' en el manifiesto (y ajusta el 'file' si hace falta).")
        print("  3. Si de verdad quieres reemplazarlas, repite con --allow-overwrite.")
        raise SystemExit(1)

    def report_untouched(entries):
        """Las páginas que se dejaron como están, con quién las editó y dónde verlas."""
        for header, state, hint in (
            ("Conservadas: editadas en Confluence, el repo no cambió", CONSERVADA,
             "No hay nada que hacer. Si quieres recuperar la versión del repo: --force-page"),
            ("Conflictos: editadas en Confluence Y cambiadas en el repo", CONFLICTO,
             "Revisa cada una y decide: portar el cambio del repo a mano, o --force-page para pisarla"),
        ):
            rows = [e for e in entries if e["state"] == state]
            if not rows:
                continue
            print(f"\n{header} ({len(rows)}):")
            for e in rows:
                version = e["existing"].get("version") or {}
                who = api.user_name(version.get("authorId"))
                when = (version.get("createdAt") or "")[:16].replace("T", " ")
                print(f"  {e['page']['title']} — v{version.get('number')} por {who} ({when})")
                print(f"    {page_url(e['existing']['id'])}")
            print(f"  {hint}")

    # ── solo informar ────────────────────────────────────────────────────────
    if args.status:
        for e in plan:
            p = e["page"]
            indent = "   " * p["depth"] + ("└─ " if p["depth"] else "")
            print(f"  {e['state']:<12} {indent}{p['title']}")
        counts = {s: sum(1 for e in plan if e["state"] == s)
                  for s in (NUEVA, ACTUALIZAR, SIN_CAMBIOS, CONSERVADA, CONFLICTO)}
        print("\n" + " · ".join(f"{n} {s}" for s, n in counts.items() if n))
        report_untouched(plan)
        print("\n--status: no se escribió nada.")
        return

    # ── entregar lo existente a Confluence ───────────────────────────────────
    if args.adopt:
        adopted = 0
        for e in plan:
            if not e["existing"]:
                continue
            value = sync_payload(e["page"]["meta"].get("id") or e["page"]["raw_title"],
                                 remote_version(e["existing"]), e["page"]["hash"],
                                 email, "adoptado")
            api.set_property(e["existing"]["id"], SYNC_PROPERTY_KEY, value, e["prop"])
            adopted += 1
            print(f"  adoptada     {e['page']['title']}")
        nuevas = sum(1 for e in plan if not e["existing"])
        print(f"\nListo: {adopted} páginas quedan en manos de Confluence. No se escribió ningún cuerpo.")
        if nuevas:
            print(f"{nuevas} páginas del manifiesto todavía no existen; una publicación normal las crea.")
        return

    # ── diagramas ────────────────────────────────────────────────────────────
    # Se renderizan todos ANTES de escribir ninguna página: si el renderizador no está,
    # mejor no publicar nada que dejar páginas con la imagen rota.
    rendered: dict[str, Path] = {}
    pending = {src for e in plan if e["state"] in ESCRIBEN for src in e["page"]["diagrams"]}
    if pending:
        if not shutil.which("npx"):
            raise SystemExit("Hay diagramas que publicar y no está npx (Node.js). Instala Node, o "
                             "publica con MERMAID_MACRO=code para subir solo la fuente plegada.")
        print(f"Renderizando {len(pending)} diagramas (caché en {MERMAID_CACHE.name}/)…")
        for src in pending:
            try:
                rendered[src] = render_diagram(src)
            except (RuntimeError, subprocess.TimeoutExpired) as exc:
                raise SystemExit(f"No se pudo renderizar un diagrama:\n{exc}\n\nFuente:\n{src[:400]}")

    # ── publicación ──────────────────────────────────────────────────────────
    ids: dict[str, str] = {}
    created = updated = skipped = 0
    broken_images: list[str] = []

    for e in plan:
        p, existing = e["page"], e["existing"]
        parent_id = ids.get(p["parent"]) if p["parent"] else root_parent

        if e["state"] not in ESCRIBEN:
            # Se salta, pero su id entra igual en el mapa: sus hijas lo necesitan como
            # padre, y sin él se irían a la raíz del espacio en esta misma corrida.
            ids[p["title"]] = existing["id"]
            skipped += 1
            if e["state"] != SIN_CAMBIOS:
                print(f"  conservada   {p['title']}  ({e['state']})")
            continue

        if existing:
            res = api.update(existing["id"], p["title"], p["body"],
                             remote_version(existing), parent_id)
            updated += 1
            print(f"  actualizada  {p['title']}")
        else:
            res = api.create(space_id, p["title"], p["body"], parent_id)
            created += 1
            print(f"  creada       {p['title']}")

        ids[p["title"]] = res["id"]
        api.add_labels(res["id"], p["labels"])
        if p["diagrams"]:
            failed = [src for src in dict.fromkeys(p["diagrams"])
                      if not api.upload_attachment(res["id"], rendered[src])]
            if failed:
                broken_images.append(p["title"])
            # Si subir un adjunto creó versión, sellar la de la escritura haría creer a la
            # próxima corrida que una persona editó la página.
            res = api.page(res["id"])
        # El sellado va al final y con la versión vigente: es lo que la próxima corrida
        # comparará para saber si alguien editó la página.
        api.set_property(res["id"], SYNC_PROPERTY_KEY,
                         sync_payload(p["meta"].get("id") or p["raw_title"],
                                      remote_version(res), p["hash"], email, "repo"),
                         e["prop"])

    print(f"\nListo: {created} creadas, {updated} actualizadas, {skipped} sin tocar. "
          f"No se borró nada.")
    if broken_images:
        print(f"\nAviso: no se pudieron subir diagramas de {len(broken_images)} páginas; se ven "
              f"como imagen rota. Repite con --force-page para cada una:")
        for title in broken_images:
            print(f'  --force-page "{title}"')
    report_untouched(plan)
    print(f"\n{base.rstrip('/')}/wiki/spaces/{space_key}")


def archive_orphans(api, space_id, space_key, root_parent, pages, args, page_url):
    """Mueve bajo la página de archivo lo que este publicador creó y ya no se declara.

    El publicador nunca borra, y tras reorganizar el árbol las páginas viejas seguirían
    apareciendo en la navegación. Esto las aparta sin perder nada —ni la página, ni su
    historial, ni los enlaces que alguien guardó— y es reversible moviéndolas de vuelta.

    Solo toca páginas con el sello del publicador: lo que creó una persona no es suyo. Una
    página sellada que alguien editó después se reporta y se salta (salvo --force), porque
    lo editado debería portarse al Markdown antes de archivarla.
    """
    if args.title_prefix or args.manifest.resolve() != MANIFEST.resolve():
        raise SystemExit("--archive-orphans solo corre contra el manifiesto real y sin "
                         "--title-prefix: con un manifiesto parcial archivaría el resto del árbol.")
    archive_title = ARCHIVE_TITLE
    archive = api.find_page(space_id, archive_title)
    if not archive:
        raise SystemExit(f"No existe la página '{archive_title}'. Declárala en el manifiesto y "
                         "publica antes de archivar.")
    declared = {p["title"] for p in pages}
    archive_id = str(archive["id"])

    sealed, edited = [], []
    for pg in api.pages_under(space_key, root_parent):
        pid = str(pg["id"])
        ancestors = {str(a["id"]) for a in pg.get("ancestors") or []}
        if pg["title"] in declared or pid == archive_id or archive_id in ancestors:
            continue
        prop = api.get_property(pid, SYNC_PROPERTY_KEY)
        if not prop:
            continue
        value = prop.get("value") or {}
        number = (pg.get("version") or {}).get("number") or 0
        entry = {"id": pid, "title": pg["title"], "ancestors": ancestors, "prop": prop}
        if (number > int(value.get("version") or 0) or value.get("origin") == "adoptado") \
                and not args.force:
            edited.append(entry)
        else:
            sealed.append(entry)

    ids = {e["id"] for e in sealed}
    # Mover una página arrastra su subárbol: basta con mover las de más arriba.
    tops = [e for e in sealed if not (e["ancestors"] & ids)]

    print(f"\nHuérfanas selladas: {len(sealed)} ({len(tops)} raíces a mover)")
    for e in sealed:
        print(f"  {'mover ' if e in tops else '  con su padre '} {e['title']}")
    if edited:
        print(f"\nEditadas en Confluence, no se archivan ({len(edited)}). Porta su contenido al "
              "Markdown y repite, o usa --force:")
        for e in edited:
            print(f"  {e['title']}\n    {page_url(e['id'])}")

    if args.status:
        print("\n--status: no se movió nada.")
        return
    for e in tops:
        api.move_under(e["id"], archive_id)
        print(f"  archivada    {e['title']}")
    for e in sealed:
        api.add_labels(e["id"], [ARCHIVE_LABEL])
    print(f"\nListo: {len(tops)} movidas bajo '{archive_title}' ({len(sealed)} páginas en total). "
          "No se borró nada.")


if __name__ == "__main__":
    main()
