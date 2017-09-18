(ns clj-zip-meta.spec
  (:require [octet.core :refer [spec int16 int32 ref-string* ref-bytes*]]))

; 4.3.7  Local file header:
;
;     local file header signature     4 bytes  (0x04034b50)
;     version needed to extract       2 bytes
;     general purpose bit flag        2 bytes
;     compression method              2 bytes
;     last mod file time              2 bytes
;     last mod file date              2 bytes
;     crc-32                          4 bytes
;     compressed size                 4 bytes
;     uncompressed size               4 bytes
;     file name length                2 bytes
;     extra field length              2 bytes
;
;     file name (variable size)
;     extra field (variable size)
(def rec-local-file-header
  (spec
    :local-header-signature    int32                           ; size 32 + string lengths
    :version-needed-to-extract int16
    :general-purpose           int16
    :compression-method        int16
    :last-mod-file-time        int16
    :last-mod-file-date        int16
    :crc-32                    int32
    :compressed-size           int32
    :uncompressed-size         int32
    :file-name-length          int16
    :extra-field-length        int16
    :file-name                 (ref-string* :file-name-length)
    :extra-field               (ref-bytes* :extra-field-length)))

(def rec-local-file-header-sig
  {:cdr-header-signature (byte-array [0x50 0x4b 0x03 0x04])})

; 4.3.12  Central directory structure:
; central directory header n
;
;central file header signature        4 bytes  (0x02014b50)
;     version made by                 2 bytes
;     version needed to extract       2 bytes
;     general purpose bit flag        2 bytes
;     compression method              2 bytes
;     last mod file time              2 bytes
;     last mod file date              2 bytes
;     crc-32                          4 bytes
;     compressed size                 4 bytes
;     uncompressed size               4 bytes
;     file name length                2 bytes
;     extra field length              2 bytes
;     file comment length             2 bytes
;     disk number start               2 bytes
;     internal file attributes        2 bytes
;     external file attributes        4 bytes
;     relative offset of local header 4 bytes
;
;     file name (variable size)
;     extra field (variable size)
;     file comment (variable size)
(def rec-cdr-header 
  (spec  ; size 46 + string lengths
    :cdr-header-signature         int32
    :version-made-by              int16
    :version-needed-to-extract    int16
    :general-purpose              int16
    :compression-method           int16
    :last-mod-file-time           int16
    :last-mod-file-date           int16
    :crc-32                       int32
    :compressed-size              int32
    :uncompressed-size            int32
    :file-name-length             int16
    :extra-field-length           int16
    :file-comment-length          int16
    :disk-number-start            int16
    :internal-file-attributes     int16
    :external-file-attributes     int32
    :relative-offset-local-header int32
    :file-name                    (ref-string* :file-name-length)
    :extra-field                  (ref-bytes*  :extra-field-length)
    :file-comment                 (ref-string* :file-comment-length)))
(def rec-cdr-header-sig
  {:cdr-header-signature (byte-array [0x50 0x4b 0x01 0x02])})

;4.3.16  End of central directory record:
;
;      end of central dir signature    4 bytes  (0x06054b50)
;      number of this disk             2 bytes
;      number of the disk with the
;      start of the central directory  2 bytes
;      total number of entries in the
;      central directory on this disk  2 bytes
;      total number of entries in
;      the central directory           2 bytes
;      size of the central directory   4 bytes
;      offset of start of central
;      directory with respect to
;      the starting disk number        4 bytes
;      .ZIP file comment length        2 bytes
;      .ZIP file comment       (variable size)

;; The same spec but using associative composition
(def rec-end-of-cdr
  (spec
    :end-of-cdr-signature       int32
    :number-of-this-disk        int16
    :number-of-cdr-disk         int16
    :cdr-entries-this-disk      int16
    :cdr-entries-total          int16
    :cdr-size                   int32
    :cdr-offset-from-start-disk int32
    :zip-comment-length         int16
    :zip-comment                (ref-string* :zip-comment-length)))
(def rec-end-of-cdr-sig
  {:end-of-cdr-signature (byte-array [0x50 0x4b 0x05 0x06])})
