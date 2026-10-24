#!/usr/bin/env python3
"""
Converte o texto da Bíblia Almeida, Edição Revista e Corrigida (Lisboa, 1911; domínio público),
transcrito pelo Distributed Proofreaders, para os arquivos que o app lê:

    app/src/main/assets/biblia/almeida1911/<LIVRO>.txt   (linhas "capitulo<TAB>versiculo<TAB>texto")

Uso: python3 converter.py <texto_fonte.txt> <pasta_saida> <BibleBooks.kt>

O texto bíblico não é alterado: só são retiradas as chamadas de notas ([1], [A]), as marcas de
itálico (_palavra_), os títulos de seção, as notas de rodapé e as notas de data.
Sai com erro se a quantidade de capítulos de algum livro não bater com a tabela do app.
"""
import os
import re
import sys
from collections import OrderedDict

VERSE = re.compile(r"^(\d{1,3}) (.+)$", re.S)
# Lamentações e Salmo 119 são acrósticos: o versículo vem depois da letra hebraica ("ALEPH. 3 Eu sou...").
HEBREW_LETTER = re.compile(r"^[A-ZÇ]{2,8}\.\s+(?=(?:\[[^\]]+\]\s*)*\d{1,3} )")
# Chamada de nota antes do número do versículo: "[3] 3 Judah passou..." (não confundir com a nota "[3] Jer. 6.25").
LEADING_NOTE = re.compile(r"^(?:\[(?:\d{1,3}|[A-Z]{1,2})\]\s*)+(?=\d{1,3} )")
NOTE_MARK = re.compile(r"\s*\[(?:\d{1,3}|[A-Z]{1,2})\]")


def load_books(kotlin_path):
    text = open(kotlin_path, encoding="utf-8").read()
    books = re.findall(r'b\("(\w+)", "([^"]+)", (\d+)', text)
    if len(books) != 66:
        sys.exit(f"esperava 66 livros em {kotlin_path}, achei {len(books)}")
    return [(book_id, name, int(chapters)) for book_id, name, chapters in books]


# Linha que começa um versículo mesmo sem linha em branco antes ("5 Não tornará...").
VERSE_LINE = re.compile(r"^\d{1,3} (?:\[[^\]]+\] *)*[A-ZÀ-Ý«“\"(]")


def paragraphs(lines):
    """Junta linhas em parágrafos; devolve (texto, linhas_em_branco_antes)."""
    current, blanks = [], 0
    for line in lines:
        if line.strip():
            if current and VERSE_LINE.match(line.strip()) and VERSE.match(current[0]):
                yield " ".join(current), blanks
                current, blanks = [], 0
            current.append(line.strip())
        else:
            if current:
                yield " ".join(current), blanks
                current, blanks = [], 0
            blanks += 1
    if current:
        yield " ".join(current), blanks


def is_book_heading(text):
    letters = [c for c in text if c.isalpha()]
    return len(letters) >= 6 and all(c.isupper() for c in letters) and not text[0].isdigit()


def clean(text):
    text = NOTE_MARK.sub("", text)
    text = text.replace("_", "")
    return re.sub(r"\s+", " ", text).strip()


def convert(source_path, books):
    raw = open(source_path, encoding="utf-8-sig").read().replace("\r\n", "\n")
    start = raw.index("*** START OF THE PROJECT GUTENBERG EBOOK")
    end = raw.index("*** END OF THE PROJECT GUTENBERG EBOOK")
    body = raw[start:end]
    # O texto começa no primeiro livro (depois do índice e da nota do transcritor).
    body = body[body.index("O PRIMEIRO LIVRO DE MOYS"):]

    # 1ª passada: só os parágrafos de versículo, com os sinais que vieram antes de cada um.
    items = []           # [(numero, texto, troca_de_capitulo_provavel, titulo_de_livro_antes)]
    skipped = []         # parágrafos ignorados antes de cada versículo (para mensagens de erro)
    skipped_now = []
    heading_since_verse = True
    boundary = True      # notas de rodapé ou espaço grande desde o último versículo
    for text, blanks in paragraphs(body.split("\n")):
        skipped_now.append(text[:90])
        if blanks >= 3:
            boundary = True
        verse_text = LEADING_NOTE.sub("", HEBREW_LETTER.sub("", text))
        if text.startswith("[") and verse_text.startswith("["):   # notas de rodapé e notas de data
            boundary = True
            continue
        if text.startswith("_") and text.rstrip(".").endswith("_"):   # título de seção
            continue
        if is_book_heading(text):
            heading_since_verse = True
            continue
        m = VERSE.match(verse_text)
        if not m:
            continue                      # texto solto (ex.: subscrições de cartas)
        items.append((int(m.group(1)), clean(m.group(2)), boundary, heading_since_verse))
        skipped.append(skipped_now[:-1][-6:])
        skipped_now = []
        heading_since_verse = False
        boundary = False

    # 2ª passada: decide versículo x capítulo novo; no caso ambíguo, olha o parágrafo seguinte.
    result = []          # [(book_index, chapter, verse, text)]
    book_index = -1
    chapter = verse = 0
    for i, (n, content, boundary, new_book) in enumerate(items):
        following = items[i + 1][0] if i + 1 < len(items) else None
        book_done = book_index < 0 or chapter >= books[book_index][2]
        if n == 1 and (new_book or book_done) and (book_done or verse > 1):
            book_index += 1
            chapter, verse = 1, 1
        elif boundary and n == chapter + 1 and n == verse + 1:
            # Ex.: versículo 10 do capítulo 9 depois de um título x começo do capítulo 10.
            if following == 2:
                chapter, verse = n, 1
            else:
                verse = n
        elif n == verse + 1:
            verse = n
        elif boundary and n == chapter + 1:
            chapter, verse = n, 1
        elif verse + 1 < n <= verse + 5 and result and split_embedded(result, verse, n):
            verse = n                         # versículos que estavam colados no parágrafo anterior
        elif following == verse + 2:
            # Número errado na própria fonte (ex.: Marcos 4.34 impresso como "31"): a sequência
            # em volta mostra qual é o versículo certo.
            WARNINGS.append(f"{books[book_index][0]} {chapter}:{verse + 1} estava numerado {n} na fonte; corrigido")
            verse += 1
        elif boundary and following == 2:
            WARNINGS.append(f"{books[book_index][0]}: capítulo {chapter + 1} começava com o número {n} na fonte; corrigido")
            chapter, verse = chapter + 1, 1
        else:
            print("parágrafos ignorados antes do erro:")
            for t in skipped[i]:
                print("   |", t)
            raise SystemExit(
                f"sequência inesperada em {books[book_index][0]} {chapter}:{verse} -> parágrafo {n}: {content[:60]!r}"
            )
        result.append((book_index, chapter, verse, content))
    return result


WARNINGS = []


def split_embedded(result, verse, n):
    """Separa "4 texto... 5 texto..." quando os versículos verse+1..n-1 ficaram dentro do anterior."""
    book_index, chapter, last_verse, text = result[-1]
    pieces = [text]
    for missing in range(verse + 1, n):
        m = re.search(rf"(?<=[.;:!?,]) {missing} (?=[A-ZÀ-Ý«“\"(])", pieces[-1])
        if not m:
            return False
        pieces[-1:] = [pieces[-1][:m.start()].strip(), pieces[-1][m.end():].strip()]
    result[-1] = (book_index, chapter, last_verse, pieces[0])
    for offset, piece in enumerate(pieces[1:], start=1):
        result.append((book_index, chapter, last_verse + offset, piece))
    return True


def validate(verses, books):
    problems = []
    by_book = OrderedDict()
    for book_index, chapter, verse, _ in verses:
        by_book.setdefault(book_index, {}).setdefault(chapter, []).append(verse)
    if len(by_book) != 66:
        problems.append(f"livros encontrados: {len(by_book)} (esperado 66)")
    for book_index, (book_id, name, expected) in enumerate(books):
        chapters = by_book.get(book_index, {})
        if len(chapters) != expected:
            problems.append(f"{book_id}: {len(chapters)} capítulos (esperado {expected})")
        for chapter, numbers in chapters.items():
            if numbers != list(range(1, len(numbers) + 1)):
                problems.append(f"{book_id} {chapter}: versículos fora de ordem {numbers[:5]}...")
    return problems


def main():
    source, out_dir, kotlin = sys.argv[1:4]
    books = load_books(kotlin)
    verses = convert(source, books)
    problems = validate(verses, books)
    print(f"versículos: {len(verses)}")
    print(f"correções de numeração da fonte: {len(WARNINGS)}")
    for w in WARNINGS:
        print("  corrigido:", w)
    for p in problems:
        print("PROBLEMA:", p)

    lookup = {(books[b][0], c, v): t for b, c, v, t in verses}
    for ref in [("GEN", 1, 1), ("PSA", 23, 1), ("JHN", 3, 16), ("1PE", 5, 7), ("REV", 22, 21)]:
        print(f"{ref[0]} {ref[1]}:{ref[2]} = {lookup.get(ref, '<faltando>')}")
    if problems:
        sys.exit(1)

    os.makedirs(out_dir, exist_ok=True)
    for book_index, (book_id, _, _) in enumerate(books):
        with open(os.path.join(out_dir, f"{book_id}.txt"), "w", encoding="utf-8", newline="\n") as f:
            for b, c, v, t in verses:
                if b == book_index:
                    f.write(f"{c}\t{v}\t{t}\n")
    print(f"arquivos gerados em {out_dir}")


if __name__ == "__main__":
    main()
