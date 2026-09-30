-- Recorded pronunciations of dictionary words. A row holds a Wikimedia Commons file name, not the audio: the browser
-- plays the file from upload.wikimedia.org, and the URL is derived from the name (see `CommonsAudio`). `region` is
-- the recording's accent tags as the dump states them (`Germany, Berlin`, `US`), or '' where it gives none.
-- `DictionaryImport` is the only writer.
CREATE TABLE word_audio (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    word_id    BIGINT NOT NULL REFERENCES words(id) ON DELETE CASCADE,
    file_name  VARCHAR(255) NOT NULL,
    region     VARCHAR(255) NOT NULL,
    created_at BIGINT NOT NULL,
    UNIQUE (word_id, file_name)
);
