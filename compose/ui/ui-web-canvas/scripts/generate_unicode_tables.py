#!/usr/bin/env python3
#
# Copyright 2026 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
"""Generates the Unicode property tables of the web Canvas2D text engine.

The script downloads the required files of the Unicode Character Database (UCD) into a temporary
directory (or reads them from --ucd-dir) and writes two Kotlin files:

  src/webMain/kotlin/androidx/compose/ui/webcanvas/text/unicode/UnicodeTables.kt
      Compactly encoded property tables (see "Encoding" below), decoded once at runtime by
      UnicodePropertyTable.kt.

  src/webTest/kotlin/androidx/compose/ui/webcanvas/text/unicode/UnicodeConformanceTestData.kt
      Test cases taken from the official conformance test files (LineBreakTest.txt,
      BidiCharacterTest.txt). By default only a deterministic subset of them is embedded to keep
      the test sources small; pass --full-test-data to embed every test case (useful for local
      verification, do not check that in).

Only the properties of the algorithms that browsers don't expose are generated: line breaking
(UAX #14) and the bidirectional algorithm (UAX #9). Grapheme clusters and words come from the
browser's `Intl.Segmenter`.

Usage:
  python3 compose/ui/ui-web-canvas/scripts/generate_unicode_tables.py [--ucd-dir DIR]
      [--unicode-version 18.0.0] [--full-test-data]

Encoding:
  Every table is a raw string literal (newlines are ignored by the decoder) holding a sequence
  of unsigned integers in a base-46 variable-length encoding over 92 printable ASCII symbols
  (0x21..0x7E without '"' and '$', so that the text is a valid raw string literal). Symbols
  0..45 are final digits, symbols 46..91 are continuation digits (most significant first).

  A property table is encoded as
      K value[0] .. value[K-1]  R  run[0] .. run[R-1]
  where the runs cover all code points 0..10FFFF in order and
      run[i] = (length[i] - 1) * K + index of the run's value in value[].
"""

import argparse
import os
import shutil
import sys
import tempfile
import urllib.request

DEFAULT_UNICODE_VERSION = '18.0.0'

UCD_FILES = {
    'DerivedBidiClass.txt': 'ucd/extracted/DerivedBidiClass.txt',
    'DerivedGeneralCategory.txt': 'ucd/extracted/DerivedGeneralCategory.txt',
    'BidiBrackets.txt': 'ucd/BidiBrackets.txt',
    'LineBreak.txt': 'ucd/LineBreak.txt',
    'EastAsianWidth.txt': 'ucd/EastAsianWidth.txt',
    'emoji-data.txt': 'ucd/emoji/emoji-data.txt',
    'LineBreakTest.txt': 'ucd/auxiliary/LineBreakTest.txt',
    'BidiCharacterTest.txt': 'ucd/BidiCharacterTest.txt',
}

CODE_POINT_COUNT = 0x110000

LICENSE_HEADER = '''/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
'''

PACKAGE = 'androidx.compose.ui.webcanvas.text.unicode'

# --------------------------------------------------------------------------------------------
# Property value numbering. These lists define the integer values used by the Kotlin code and
# MUST be kept in sync with the constants in BidiClass, LineBreakClass and the emoji flags in the
# Kotlin sources.
# --------------------------------------------------------------------------------------------

BIDI_CLASSES = ['L', 'R', 'AL', 'EN', 'ES', 'ET', 'AN', 'CS', 'NSM', 'BN', 'B', 'S', 'WS', 'ON',
                'LRE', 'LRO', 'RLE', 'RLO', 'PDF', 'LRI', 'RLI', 'FSI', 'PDI']

BIDI_LONG_NAMES = {
    'Left_To_Right': 'L', 'Right_To_Left': 'R', 'Arabic_Letter': 'AL', 'European_Number': 'EN',
    'European_Separator': 'ES', 'European_Terminator': 'ET', 'Arabic_Number': 'AN',
    'Common_Separator': 'CS', 'Nonspacing_Mark': 'NSM', 'Boundary_Neutral': 'BN',
    'Paragraph_Separator': 'B', 'Segment_Separator': 'S', 'White_Space': 'WS',
    'Other_Neutral': 'ON', 'Left_To_Right_Embedding': 'LRE', 'Left_To_Right_Override': 'LRO',
    'Right_To_Left_Embedding': 'RLE', 'Right_To_Left_Override': 'RLO',
    'Pop_Directional_Format': 'PDF', 'Left_To_Right_Isolate': 'LRI',
    'Right_To_Left_Isolate': 'RLI', 'First_Strong_Isolate': 'FSI',
    'Pop_Directional_Isolate': 'PDI',
}

LINE_BREAK_CLASSES = ['BK', 'CR', 'LF', 'NL', 'SP', 'ZW', 'ZWJ', 'CM', 'WJ', 'GL',
                      'AI', 'AK', 'AL', 'AP', 'AS', 'B2', 'BA', 'BB', 'CB', 'CJ',
                      'CL', 'CP', 'EB', 'EM', 'EX', 'H2', 'H3', 'HH', 'HL', 'HY',
                      'ID', 'IN', 'IS', 'JL', 'JT', 'JV', 'NS', 'NU', 'OP', 'PO',
                      'PR', 'QU', 'RI', 'SA', 'SG', 'SY', 'VF', 'VI', 'XX']
# Internal pseudo classes stored in the line break table (see LineBreak.kt). They fold the few
# extra properties needed by the line breaking rules into the Line_Break table.
LB_SA_MARK = len(LINE_BREAK_CLASSES)  # SA with General_Category Mn or Mc (LB1: resolves to CM)
LB_QU_PI = LB_SA_MARK + 1  # QU with General_Category Pi (LB15a, LB19)
LB_QU_PF = LB_SA_MARK + 2  # QU with General_Category Pf (LB15b, LB19)

# Only used while generating: General_Category is folded into the other tables.
GENERAL_CATEGORIES = ['Cn', 'Lu', 'Ll', 'Lt', 'Lm', 'Lo', 'Mn', 'Me', 'Mc', 'Nd', 'Nl', 'No',
                      'Zs', 'Zl', 'Zp', 'Cc', 'Cf', 'Co', 'Cs', 'Pd', 'Ps', 'Pe', 'Pc', 'Po',
                      'Sm', 'Sc', 'Sk', 'So', 'Pi', 'Pf']

EMOJI_PROPERTIES = ['Emoji', 'Emoji_Presentation', 'Emoji_Modifier', 'Emoji_Modifier_Base',
                    'Emoji_Component', 'Extended_Pictographic']
# An extra bit of the emoji table: Extended_Pictographic code points that are unassigned
# (General_Category Cn), reserved for future emoji (LB30b).
EMOJI_UNASSIGNED_EXT_PICT_BIT = 1 << len(EMOJI_PROPERTIES)

# --------------------------------------------------------------------------------------------
# UCD parsing
# --------------------------------------------------------------------------------------------


def parse_code_points(field):
    field = field.strip()
    if '..' in field:
        first, last = field.split('..')
        return int(first, 16), int(last, 16)
    cp = int(field, 16)
    return cp, cp


def read_data_lines(path):
    """Yields (first, last, [fields...]) for each data line of a UCD file."""
    with open(path, encoding='utf-8') as f:
        for line in f:
            data = line.split('#', 1)[0].strip()
            if not data:
                continue
            fields = [x.strip() for x in data.split(';')]
            first, last = parse_code_points(fields[0])
            yield first, last, fields[1:]


def read_missing_lines(path):
    """Yields (first, last, [fields...]) for each '# @missing:' line of a UCD file."""
    with open(path, encoding='utf-8') as f:
        for line in f:
            if line.startswith('# @missing:'):
                data = line[len('# @missing:'):].split('#', 1)[0].strip()
                fields = [x.strip() for x in data.split(';')]
                first, last = parse_code_points(fields[0])
                yield first, last, fields[1:]


def enumerated_property(path, values, default, aliases=None, prop_name=None):
    """Returns a list mapping every code point to the index of its property value in values."""
    aliases = aliases or {}
    index = {v: i for i, v in enumerate(values)}

    def value_index(name):
        return index[aliases.get(name, name)]

    table = [value_index(default)] * CODE_POINT_COUNT
    for first, last, fields in read_missing_lines(path):
        if prop_name is not None:
            if fields[0] != prop_name:
                continue
            fields = fields[1:]
        v = value_index(fields[0])
        for cp in range(first, last + 1):
            table[cp] = v
    for first, last, fields in read_data_lines(path):
        if prop_name is not None:
            if fields[0] != prop_name:
                continue
            fields = fields[1:]
        v = value_index(fields[0])
        for cp in range(first, last + 1):
            table[cp] = v
    return table


def binary_properties(path, names):
    """Returns {name: set of code points} for the requested binary properties."""
    result = {name: set() for name in names}
    for first, last, fields in read_data_lines(path):
        if fields[0] in result:
            result[fields[0]].update(range(first, last + 1))
    return result


# --------------------------------------------------------------------------------------------
# Encoding
# --------------------------------------------------------------------------------------------

SYMBOLS = [chr(c) for c in range(0x21, 0x7F) if chr(c) not in '"$']
assert len(SYMBOLS) == 92
BASE = 46


def encode_varint(n, out):
    assert n >= 0
    digits = []
    while True:
        digits.append(n % BASE)
        n //= BASE
        if n == 0:
            break
    digits.reverse()
    for d in digits[:-1]:
        out.append(SYMBOLS[BASE + d])
    out.append(SYMBOLS[digits[-1]])


def zigzag(n):
    return (n << 1) if n >= 0 else ((-n << 1) - 1)


def runs_of(table):
    runs = []
    start = 0
    for cp in range(1, CODE_POINT_COUNT + 1):
        if cp == CODE_POINT_COUNT or table[cp] != table[start]:
            runs.append((start, cp - start, table[start]))
            start = cp
    return runs


def encode_property_table(table):
    runs = runs_of(table)
    values = sorted(set(v for _, _, v in runs))
    value_index = {v: i for i, v in enumerate(values)}
    k = len(values)
    out = []
    encode_varint(k, out)
    for v in values:
        encode_varint(v, out)
    encode_varint(len(runs), out)
    for _, length, v in runs:
        encode_varint((length - 1) * k + value_index[v], out)
    return ''.join(out), len(runs)


def raw_string_literal(text, width=100):
    lines = [text[i:i + width] for i in range(0, len(text), width)]
    body = '\n'.join(lines)
    assert '"""' not in body and '$' not in body
    return '"""\n' + body + '\n"""'


# Large strings are split into several literals joined at runtime, which keeps every literal well
# below the 64 KB limit of JVM class files (should the sources ever be compiled for the JVM).
MAX_LITERAL_LENGTH = 30000


def string_property(name, text):
    if len(text) <= MAX_LITERAL_LENGTH:
        return 'internal const val %s: String =\n    %s\n' % (name, raw_string_literal(text))
    chunks = [text[i:i + MAX_LITERAL_LENGTH] for i in range(0, len(text), MAX_LITERAL_LENGTH)]
    literals = ''.join('            %s,\n' % raw_string_literal(chunk) for chunk in chunks)
    # Formatted like ktfmt does, so that formatting the generated file is a no-op.
    return ('internal val %s: String =\n    arrayOf(\n%s        )\n        .joinToString("")\n'
            % (name, literals))


def kdoc(text):
    line = '/** %s */' % text
    assert len(line) <= 100, 'KDoc too long for a single line: ' + text
    return line + '\n'


# --------------------------------------------------------------------------------------------
# Table generation
# --------------------------------------------------------------------------------------------


def build_tables(ucd):
    general_category = enumerated_property(
        os.path.join(ucd, 'DerivedGeneralCategory.txt'), GENERAL_CATEGORIES, 'Cn')

    bidi = enumerated_property(
        os.path.join(ucd, 'DerivedBidiClass.txt'), BIDI_CLASSES, 'L', aliases=BIDI_LONG_NAMES)

    emoji_sets = binary_properties(os.path.join(ucd, 'emoji-data.txt'), EMOJI_PROPERTIES)
    emoji = [0] * CODE_POINT_COUNT
    for bit, name in enumerate(EMOJI_PROPERTIES):
        for cp in emoji_sets[name]:
            emoji[cp] |= 1 << bit
    unassigned = GENERAL_CATEGORIES.index('Cn')
    for cp in emoji_sets['Extended_Pictographic']:
        if general_category[cp] == unassigned:
            emoji[cp] |= EMOJI_UNASSIGNED_EXT_PICT_BIT

    line_break = enumerated_property(
        os.path.join(ucd, 'LineBreak.txt'), LINE_BREAK_CLASSES, 'XX')
    sa = LINE_BREAK_CLASSES.index('SA')
    qu = LINE_BREAK_CLASSES.index('QU')
    mn, mc = GENERAL_CATEGORIES.index('Mn'), GENERAL_CATEGORIES.index('Mc')
    pi, pf = GENERAL_CATEGORIES.index('Pi'), GENERAL_CATEGORIES.index('Pf')
    for cp in range(CODE_POINT_COUNT):
        lb = line_break[cp]
        gc = general_category[cp]
        if lb == sa and gc in (mn, mc):
            line_break[cp] = LB_SA_MARK
        elif lb == qu and gc == pi:
            line_break[cp] = LB_QU_PI
        elif lb == qu and gc == pf:
            line_break[cp] = LB_QU_PF

    east_asian = enumerated_property(
        os.path.join(ucd, 'EastAsianWidth.txt'), ['N', 'A', 'H', 'W', 'F', 'Na'], 'N')
    # 1 for the $EastAsian set of UAX #14: [\p{ea=F}\p{ea=W}\p{ea=H}].
    east_asian = [1 if v in (2, 3, 4) else 0 for v in east_asian]

    tables = [
        ('BIDI_CLASS_DATA', 'Bidi_Class (BidiClass values).', bidi),
        ('LINE_BREAK_DATA',
         'Line_Break (LineBreakClass values and the internal pseudo classes of LineBreak.kt).',
         line_break),
        ('EAST_ASIAN_DATA',
         '1 for East_Asian_Width F, W or H (the $EastAsian set of UAX #14), else 0.', east_asian),
        ('EMOJI_DATA', 'Emoji properties, one bit each (see the constants in CharProperties.kt).',
         emoji),
    ]
    return tables


def build_brackets(ucd):
    entries = []
    for first, last, fields in read_data_lines(os.path.join(ucd, 'BidiBrackets.txt')):
        assert first == last
        paired = int(fields[0], 16)
        kind = fields[1]
        assert kind in ('o', 'c')
        entries.append((first, paired, 0 if kind == 'o' else 1))
    entries.sort()
    out = []
    encode_varint(len(entries), out)
    prev = 0
    for cp, paired, kind in entries:
        encode_varint(cp - prev, out)
        encode_varint(zigzag(paired - cp) * 2 + kind, out)
        prev = cp
    return ''.join(out), len(entries)


def write_tables(ucd, version, path):
    parts = [LICENSE_HEADER, '\n']
    parts.append('// GENERATED FILE - DO NOT EDIT.\n')
    parts.append('//\n')
    parts.append('// Generated by compose/ui/ui-web-canvas/scripts/generate_unicode_tables.py from the\n')
    parts.append('// Unicode Character Database %s. To regenerate (e.g. for a new Unicode version), run\n'
                 % version)
    parts.append('//   python3 compose/ui/ui-web-canvas/scripts/generate_unicode_tables.py\n')
    parts.append('// from the repository root. See the script for a description of the encoding, which is\n')
    parts.append('// decoded by UnicodePropertyTable.kt.\n\n')
    parts.append('package %s\n' % PACKAGE)

    stats = []
    for name, doc, table in build_tables(ucd):
        encoded, run_count = encode_property_table(table)
        stats.append((name, run_count, len(encoded)))
        parts.append('\n' + kdoc(doc))
        parts.append(string_property(name, encoded))

    encoded, count = build_brackets(ucd)
    stats.append(('BIDI_BRACKETS_DATA', count, len(encoded)))
    parts.append('\n/**\n * BidiBrackets.txt: for each entry, the code point delta to the previous entry and\n'
                 ' * `zigzag(Bidi_Paired_Bracket - code point) * 2 + (0 for open, 1 for close)`.\n */\n')
    parts.append(string_property('BIDI_BRACKETS_DATA', encoded))

    source = ''.join(parts)
    with open(path, 'w', encoding='utf-8') as f:
        f.write(source)
    return stats, len(source.encode('utf-8'))


# --------------------------------------------------------------------------------------------
# Conformance test data
# --------------------------------------------------------------------------------------------


class TestDataEncoder:
    """Encodes test cases; code points are stored as indices into a per-file alphabet."""

    def __init__(self):
        self.alphabet = []
        self.alphabet_index = {}
        self.body = []
        self.count = 0

    def code_point(self, cp):
        index = self.alphabet_index.get(cp)
        if index is None:
            index = len(self.alphabet)
            self.alphabet.append(cp)
            self.alphabet_index[cp] = index
        encode_varint(index, self.body)

    def number(self, n):
        encode_varint(n, self.body)

    def bits(self, flags):
        """Encodes a list of booleans in chunks of 15 bits."""
        for i in range(0, len(flags), 15):
            chunk = 0
            for j, flag in enumerate(flags[i:i + 15]):
                if flag:
                    chunk |= 1 << j
            encode_varint(chunk, self.body)

    def encode(self):
        out = []
        encode_varint(len(self.alphabet), out)
        prev = 0
        for cp in self.alphabet:
            encode_varint(zigzag(cp - prev), out)
            prev = cp
        encode_varint(self.count, out)
        return ''.join(out) + ''.join(self.body)


def read_break_tests(path):
    """Yields (code points, break flags at positions 0..n) for each test of a *BreakTest.txt."""
    with open(path, encoding='utf-8') as f:
        for line in f:
            data = line.split('#', 1)[0].strip()
            if not data:
                continue
            cps = []
            breaks = []
            for token in data.split():
                if token == '\u00F7':  # DIVISION SIGN, a break
                    breaks.append(True)
                elif token == '\u00D7':  # MULTIPLICATION SIGN, no break
                    breaks.append(False)
                else:
                    cps.append(int(token, 16))
            assert len(breaks) == len(cps) + 1
            yield cps, breaks


def encode_break_tests(path, keep):
    """Format per test: n, n code points, break flags of positions 0..n (15-bit chunks)."""
    enc = TestDataEncoder()
    total = 0
    for i, (cps, breaks) in enumerate(read_break_tests(path)):
        total += 1
        if not keep(i, cps):
            continue
        enc.count += 1
        enc.number(len(cps))
        for cp in cps:
            enc.code_point(cp)
        enc.bits(breaks)
    return enc, total


def encode_bidi_character_tests(path, keep):
    """Format per test: n, n code points, paragraph direction (0, 1, 2), resolved paragraph
    level, n levels (level + 1, or 0 for characters removed by X9), m = number of characters in
    the visual order, m logical indices in visual order."""
    enc = TestDataEncoder()
    total = 0
    in_permutations = False
    with open(path, encoding='utf-8') as f:
        for line in f:
            if line.startswith('# Permutations of sequences'):
                in_permutations = True
            data = line.split('#', 1)[0].strip()
            if not data:
                continue
            fields = data.split(';')
            cps = [int(x, 16) for x in fields[0].split()]
            total += 1
            if not keep(total - 1, in_permutations):
                continue
            levels = fields[3].split()
            order = [int(x) for x in fields[4].split()]
            enc.count += 1
            enc.number(len(cps))
            for cp in cps:
                enc.code_point(cp)
            enc.number(int(fields[1]))
            enc.number(int(fields[2]))
            for level in levels:
                enc.number(0 if level == 'x' else int(level) + 1)
            enc.number(len(order))
            for index in order:
                enc.number(index)
    return enc, total


def write_test_data(ucd, version, path, full):
    if full:
        line_break_keep = lambda i, cps: True
        bidi_keep = lambda i, in_permutations: True
    else:
        # LineBreakTest.txt: every 3rd generated pair test plus all longer (real text) tests.
        line_break_keep = lambda i, cps: len(cps) > 4 or i % 3 == 0
        # BidiCharacterTest.txt: all hand-written tests plus every 45th generated permutation.
        bidi_keep = lambda i, in_permutations: not in_permutations or i % 45 == 0

    parts = [LICENSE_HEADER, '\n']
    parts.append('// GENERATED FILE - DO NOT EDIT.\n')
    parts.append('//\n')
    parts.append('// Test cases from the Unicode %s conformance test files (%s),\n'
                 % (version, 'all of them' if full else 'a subset of the large files'))
    parts.append('// generated by compose/ui/ui-web-canvas/scripts/generate_unicode_tables.py. See the\n')
    parts.append('// script for the format, which is decoded by UnicodeConformanceTest.kt.\n\n')
    parts.append('package %s\n' % PACKAGE)

    stats = []
    for name, file_name, encoder in [
        ('LINE_BREAK_TEST_DATA', 'LineBreakTest.txt',
         lambda p: encode_break_tests(p, line_break_keep)),
        ('BIDI_CHARACTER_TEST_DATA', 'BidiCharacterTest.txt',
         lambda p: encode_bidi_character_tests(p, bidi_keep)),
    ]:
        enc, total = encoder(os.path.join(ucd, file_name))
        encoded = enc.encode()
        stats.append((name, enc.count, total, len(encoded)))
        parts.append('\n' + kdoc('%d of the %d test cases of %s.' % (enc.count, total, file_name)))
        parts.append(string_property(name, encoded))

    source = ''.join(parts)
    with open(path, 'w', encoding='utf-8') as f:
        f.write(source)
    return stats, len(source.encode('utf-8'))


# --------------------------------------------------------------------------------------------


def download(version, directory):
    for name, remote in UCD_FILES.items():
        url = 'https://www.unicode.org/Public/%s/%s' % (version, remote)
        target = os.path.join(directory, name)
        print('Downloading %s' % url)
        with urllib.request.urlopen(url) as response, open(target, 'wb') as out:
            shutil.copyfileobj(response, out)


def main():
    parser = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    parser.add_argument('--unicode-version', default=DEFAULT_UNICODE_VERSION)
    parser.add_argument('--ucd-dir', help='read the UCD files from this directory instead of '
                                          'downloading them')
    parser.add_argument('--full-test-data', action='store_true',
                        help='embed all conformance test cases (large, for local verification)')
    args = parser.parse_args()

    module_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    package_path = PACKAGE.replace('.', os.sep)
    tables_path = os.path.join(module_dir, 'src', 'webMain', 'kotlin', package_path,
                               'UnicodeTables.kt')
    test_data_path = os.path.join(module_dir, 'src', 'webTest', 'kotlin', package_path,
                                  'UnicodeConformanceTestData.kt')

    temp_dir = None
    ucd = args.ucd_dir
    if ucd is None:
        temp_dir = tempfile.mkdtemp(prefix='ucd-')
        ucd = temp_dir
        download(args.unicode_version, ucd)
    try:
        stats, size = write_tables(ucd, args.unicode_version, tables_path)
        print('Wrote %s (%d bytes)' % (tables_path, size))
        for name, count, length in stats:
            print('  %-24s %6d entries %7d chars' % (name, count, length))
        stats, size = write_test_data(ucd, args.unicode_version, test_data_path,
                                      args.full_test_data)
        print('Wrote %s (%d bytes)' % (test_data_path, size))
        for name, count, total, length in stats:
            print('  %-26s %6d of %6d tests %7d chars' % (name, count, total, length))
    finally:
        if temp_dir is not None:
            shutil.rmtree(temp_dir)


if __name__ == '__main__':
    sys.exit(main())
