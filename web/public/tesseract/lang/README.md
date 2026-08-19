# Tesseract trained data

`eng.traineddata.gz`, `deu.traineddata.gz`, `hun.traineddata.gz` — the OCR language models
[`ImageOcr`](../../../../modules/frontend/src/main/scala/gathedge/frontend/ocr/ImageOcr.scala) loads for the
bulk-upload image input, one per [`WordLanguage`](../../../../modules/shared/shared/src/main/scala/gathedge/shared/domain/Word.scala).

Committed here rather than fetched at build time — the same "imported, not migrated" reasoning
`DictionaryImport --seed`'s committed sample dictionary follows (see `CLAUDE.md`'s Vocabulary section) — and
committed rather than left on `tesseract.js`'s CDN default, so a bulk-upload scan works offline-first and never
depends on a third-party host being reachable.

**Source**: the `4.0.0_best_int` (LSTM, integer-quantized — smaller and faster than the float model, adequate for
this app's OCR-then-dictionary-correction use) build from
[tesseract-ocr/tessdata_fast](https://github.com/tesseract-ocr/tessdata_fast), mirrored on npm as
[`@tesseract.js-data`](https://www.npmjs.com/package/@tesseract.js-data) and fetched from its jsDelivr CDN copy —
the same source `tesseract.js` itself falls back to when no `langPath` is configured.

**Licence**: Apache License 2.0 (tessdata_fast).

To refresh or add a language, re-download from
`https://cdn.jsdelivr.net/npm/@tesseract.js-data/<lang>/4.0.0_best_int/<lang>.traineddata.gz`, where `<lang>` is the
ISO 639-2/T code `WordLanguage`'s `code` mapping in `ImageOcr` uses (`eng`, `deu`, `hun`).
