# What it is 

clj-zip-meta reads zip (or jar) files and returns the contained meta-data (as defined by the [zip file specification](https://pkware.cachefly.net/webdocs/casestudies/APPNOTE.TXT)) as clojure data structures.

A zip/jar file has the general internal format: 

```
      [local file header 1]
      [encryption header 1]
      [file data 1]
      [data descriptor 1]
      . 
      .
      .
      [local file header n]
      [encryption header n]
      [file data n]
      [data descriptor n]
      
      [archive decryption header] 
      [archive extra data record] 
      
      [central directory header 1]
      .
      .
      .
      [central directory header n]
      [zip64 end of central directory record]
      [zip64 end of central directory locator] 
      [end of central directory record]
```

This library reads the meta-data (local headers, central directory headers, and end of central directory record) as god and the zip file specification intended, by scanning from the end of the file for the end-of-central-directory record and locating the central directory based on the end-of-central-directory record. This is in contrast to a number of zip implementations out there which break if the zip file has extra bytes before the data (like self extracting zip files). 


# What it is not
This library was not written to extract files or data out of zip or jar files, there are other libraries (and ZipFile, ZipInputStream etc from java) out there for that. 

# Usage
The main entry point to this library is the `zip-meta` function: 

```clojure
(zip-meta "clojure/java.classpath/0.2.2/java.classpath-0.2.2.jar")
=>
{:extra-bytes 0,
 :end-of-cdr-record {:offset 2956,
                     :record {:end-of-cdr-signature 101010256,
                              :number-of-this-disk 0,
                              :number-of-cdr-disk 0,
                              :cdr-entries-this-disk 10,
                              :cdr-entries-total 10,
                              :cdr-size 725,
                              :cdr-offset-from-start-disk 2231,
                              :zip-comment-length 0,
                              :zip-comment ""}},
 :cdr-records [{:offset 2231,
                :record {:cdr-header-signature 33639248,
                         :version-made-by 788,
                         :version-needed-to-extract 10,
                         :general-purpose 0,
                         :compression-method 0,
                         :last-mod-file-time 23314,
                         :last-mod-file-date 17450,
                         :crc-32 0,
                         :compressed-size 0,
                         :uncompressed-size 0,
                         :file-name-length 9,
                         :extra-field-length 0,
                         :file-comment-length 0,
                         :disk-number-start 0,
                         :internal-file-attributes 0,
                         :external-file-attributes 1106051088,
                         :relative-offset-local-header 0,
                         :file-name "META-INF/",
                         :extra-field #object["[B" 0x751f4691 "[B@751f4691"],
                         :file-comment ""}}
               {:offset 2286,
                :record {:cdr-header-signature 33639248,
                         :version-made-by 788,
                         :version-needed-to-extract 10,
                         :general-purpose 0,
                         :compression-method 8,
                         :last-mod-file-time 23313,
                         :last-mod-file-date 17450,
                         :crc-32 -2096663765,
                         :compressed-size 101,
                         :uncompressed-size 125,
                         :file-name-length 20,
                         :extra-field-length 0,
                         :file-comment-length 0,
                         :disk-number-start 0,
                         :internal-file-attributes 0,
                         :external-file-attributes -2119958528,
                         :relative-offset-local-header 39,
                         :file-name "META-INF/MANIFEST.MF",
                         :extra-field #object["[B" 0x47cf4618 "[B@47cf4618"],
                         :file-comment ""}}
               ... <snip>
               ],
 :local-records [{:offset 0,
                  :record {:local-header-signature 67324752,
                           :version-needed-to-extract 10,
                           :general-purpose 0,
                           :compression-method 0,
                           :last-mod-file-time 23314,
                           :last-mod-file-date 17450,
                           :crc-32 0,
                           :compressed-size 0,
                           :uncompressed-size 0,
                           :file-name-length 9,
                           :extra-field-length 0,
                           :file-name "META-INF/",
                           :extra-field #object["[B" 0x76f87490 "[B@76f87490"]}}
                 {:offset 39,
                  :record {:local-header-signature 67324752,
                           :version-needed-to-extract 10,
                           :general-purpose 0,
                           :compression-method 8,
                           :last-mod-file-time 23313,
                           :last-mod-file-date 17450,
                           :crc-32 -2096663765,
                           :compressed-size 101,
                           :uncompressed-size 125,
                           :file-name-length 20,
                           :extra-field-length 0,
                           :file-name "META-INF/MANIFEST.MF",
                           :extra-field #object["[B" 0x1415a774 "[B@1415a774"]}}
                ... <snip>
                ]}
=> 
```
(with some of the records omitted for brevity).

The keys in the above map have a one-to-one correspondence to the [zip file specification](https://pkware.cachefly.net/webdocs/casestudies/APPNOTE.TXT). Please see the specification for details on the interpretation of the fields etc. 

There are also methods for fixing a zip file with extra bytes prepended and writing changes to the zip file meta-data. I will try to document and polish these up some time soon. 


# Intent

I wrote this library to solve a very specific problem:
 
> make it possible to create executable clojure jar files (command line utilities) which do not require you to run `java -jar ` to execute them, but which are directly executable from the command line. 

This can be accomplished by making the jar file itself executable (as in `chmod +x` or checking the executable flag on windows) and then prefixing the jar file binary data with your own code which essentially does `java -jar` on the file itself. This prepending with random data is supported by the zip file specification which means that after prefixing, the jar file is still valid in the eyes of java and all other zip file manipulating tools. 

The problem is that just prepending bytes to the zip file without altering the zip file meta-data invalidates all the offsets in the zip meta and the offsets are now off by however many bytes the prepended snippet was. 

This typically results in the following kind of messaging from say unzip: 

```
$ unzip -l bad_prelude.zip 

Archive:  src/test/resources/bad_prelude.zip
warning [src/test/resources/bad_prelude.zip]:  317 extra bytes at beginning or within zipfile
  (attempting to process anyway)
  Length      Date    Time    Name
---------  ---------- -----   ----
...
...
$
```

in this case the zip file was still read since the unzip implementation does the sane thing. A lot of tools however give up at this point: 

```
$ zipdetails bad_prelude.zip 

No Central Directory found

$
```

This library allows you to update the zip meta offsets and regain zip file integrity after prepending a prelude script to the file. 

Since I already wrote support for most of the zip file  specification to solve this problem, I figured I would make this code available for others. 

Note that the aim of this library is quite different and way more detailed than that of implementations such as java's ZipFile. The goal of this library is to give the user unfettered data oriented read/write access to the binary layer of zip files as defined by the zip file specification without abstracting away any of the details.   

This also means that this implementation will be able to (within reason) read meta-data for corrupt zip files which other implementations might just give up with

# Status
This code was just created and depends on a not-yet-released version (based on a [pull request](https://github.com/funcool/octet/pull/11) by me to support some features required by this project) of octet, a clojure binary format library. 

The library was created to solve a specific problem as per the above, robustness and/or completeness might or might not come in the future. 

# Why would you do this? 
When I started looking at zip files in clojure I could not find a single library on the JVM (in clojure or otherwise) which actually reads the zip file meta-data. I'm not talking about reading entries like java's ZipFile etc, I'm talking about the actual binary format details of the zip specification. 

This library solves that problem. In other words, given a zip file, this library will let you read and manipulate the zip end-of-cetral-directory record, all the central-directory records, and all the local records. See output in the Usage section for an example of how this data can look. 

## Author 
Matias Bjarland - mbjarland@gmail.com - mbjarland on the clojure slack

## License

Copyright © 2017 Matias Bjarland

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
