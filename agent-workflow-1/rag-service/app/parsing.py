import io
import zipfile
from pathlib import Path

from pypdf import PdfReader
from docx import Document

from app.config import settings
from app.errors import RagError

SUPPORTED = {".txt", ".md", ".pdf", ".docx"}


def validate_file(filename, data):
    suffix = Path(filename).suffix.lower()
    if suffix not in SUPPORTED:
        raise RagError("UNSUPPORTED_FORMAT", "Supported formats: TXT, Markdown, PDF, DOCX", 415)
    if not data or len(data) > settings().upload_max_bytes:
        raise RagError("FILE_SIZE", "Empty file or upload limit exceeded", 413)
    if suffix == ".pdf" and not data.startswith(b"%PDF-"):
        raise RagError("INVALID_FILE", "Invalid PDF signature", 422)
    if suffix == ".docx":
        try:
            with zipfile.ZipFile(io.BytesIO(data)) as archive:
                infos = archive.infolist()
                if len(infos) > 2000 or sum(i.file_size for i in infos) > 50 * 1024 * 1024:
                    raise ValueError("archive expansion limit exceeded")
                if "word/document.xml" not in archive.namelist():
                    raise ValueError("not a DOCX")
                if any("vbaproject" in i.filename.lower() for i in infos):
                    raise ValueError("macros are not supported")
        except (zipfile.BadZipFile, ValueError) as exc:
            raise RagError("INVALID_FILE", str(exc), 422) from exc
    if suffix in {".txt", ".md"}:
        try:
            text = data.decode("utf-8-sig")
        except UnicodeDecodeError as exc:
            raise RagError("INVALID_ENCODING", "Text files must be UTF-8", 422) from exc
        if "\x00" in text:
            raise RagError("INVALID_FILE", "Binary content is not accepted as text", 422)
    return suffix


def parse(filename, data):
    suffix = validate_file(filename, data)
    blocks = []
    if suffix == ".pdf":
        reader = PdfReader(io.BytesIO(data))
        if reader.is_encrypted or len(reader.pages) > settings().max_pages:
            raise RagError("PDF_LIMIT", "Encrypted PDF or page limit exceeded", 422)
        for number, page in enumerate(reader.pages, 1):
            text = page.extract_text() or ""
            if text.strip():
                blocks.append({"text": text, "page": number, "section": None})
    elif suffix == ".docx":
        doc = Document(io.BytesIO(data))
        # Preserve body order, including tables. No links or macros are executed.
        from docx.table import Table
        from docx.text.paragraph import Paragraph
        for child in doc.element.body.iterchildren():
            if child.tag.endswith("}p"):
                text = Paragraph(child, doc).text
            elif child.tag.endswith("}tbl"):
                text = "\n".join(" | ".join(cell.text for cell in row.cells)
                                 for row in Table(child, doc).rows)
            else:
                continue
            if text.strip():
                blocks.append({"text": text, "page": None, "section": None})
    else:
        blocks = [{"text": data.decode("utf-8-sig"), "page": None, "section": None}]
    if not blocks or not any(block["text"].strip() for block in blocks):
        raise RagError("NO_TEXT", "No extractable text; scanned PDF requires the later OCR stage", 422)
    if sum(len(block["text"]) for block in blocks) > settings().max_chars:
        raise RagError("TEXT_LIMIT", "Extracted text exceeds configured limit", 422)
    return blocks

