import java.io.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.store.*;
import org.apache.lucene.util.*;

public class ShamelaIndexExporter {
    // Static constants for subdirectory names and index names
    private static final String BOOK_OUTPUT_DIR = "book_data";
    private static final String TITLE_OUTPUT_DIR = "title_data";
    private static final String BOOK_INDEX_NAME = "page";
    private static final String TITLE_INDEX_NAME = "title";
    
    // These will be set from command line arguments
    private static String BASE_INDEX_PATH;
    private static String OUTPUT_DIR;
    private static boolean TEST_MODE = false;

    public static void main(String[] args) {
        // Validate command line arguments
        if (args.length < 2) {
            System.out.println("Usage: ShamelaIndexExporter <shamela_store_path> <output_directory> [--test-single-book]");
            System.out.println("Example: ShamelaIndexExporter /path/to/shamela4/database/store ./exported_indices");
            return;
        }
        
        // Set paths from command line arguments
        BASE_INDEX_PATH = args[0];
        OUTPUT_DIR = args[1];
        
        // Check for test mode flag
        if (args.length > 2 && args[2].equals("--test-single-book")) {
            TEST_MODE = true;
            System.out.println("TEST MODE: Will process only first book found");
        }
        
        System.out.println("Starting Shamela Index Export");
        System.out.println("Source: " + BASE_INDEX_PATH);
        System.out.println("Output: " + OUTPUT_DIR);
        System.out.println();

        try {
            // Create main output directory
            File outputDirFile = new File(OUTPUT_DIR);
            if (!outputDirFile.exists()) {
                outputDirFile.mkdirs();
                System.out.println("Created output directory: " + OUTPUT_DIR);
            }

            // Create books subdirectory
            File booksDir = new File(OUTPUT_DIR + File.separator + BOOK_OUTPUT_DIR);
            if (!booksDir.exists()) {
                booksDir.mkdirs();
                System.out.println("Created books directory: " + booksDir.getPath());
            }

            // Create titles subdirectory
            File titlesDir = new File(OUTPUT_DIR + File.separator + TITLE_OUTPUT_DIR);
            if (!titlesDir.exists()) {
                titlesDir.mkdirs();
                System.out.println("Created titles directory: " + titlesDir.getPath());
            }

            // Validate base index path
            File baseDir = new File(BASE_INDEX_PATH);
            if (!baseDir.exists()) {
                System.out.println("ERROR: Base index path does not exist: " + BASE_INDEX_PATH);
                return;
            }
            
            File[] indexDirs = baseDir.listFiles(File::isDirectory);

            if (indexDirs == null || indexDirs.length == 0) {
                System.out.println("No subdirectories found in " + BASE_INDEX_PATH);
                return;
            }

            System.out.println("Found " + indexDirs.length + " index directories to process");
            System.out.println();

            for (File indexDir : indexDirs) {
                String indexName = indexDir.getName();
                System.out.println("Processing index: " + indexName);

                try {
                    Directory directory = FSDirectory.open(indexDir.toPath());

                    // Check if it's a valid Lucene index
                    if (!DirectoryReader.indexExists(directory)) {
                        System.out.println("No valid Lucene index in: " + indexDir.getPath());
                        continue;
                    }

                    IndexReader reader = DirectoryReader.open(directory);

                    if (indexName.equalsIgnoreCase(BOOK_INDEX_NAME)) {
                        // Special handling for book pages index
                        exportBookIndexToSeparateFiles(reader, indexName);
                    } else if (indexName.equalsIgnoreCase(TITLE_INDEX_NAME)) {
                        // Special handling for title index
                        exportTitleIndexToSeparateFiles(reader, indexName);
                    } else {
                        // Standard export for other indices
                        exportStandardIndex(reader, indexName);
                    }

                    reader.close();
                    directory.close();

                } catch (IndexFormatTooNewException e) {
                    System.out.println("Index format too new in: " + indexDir.getPath() +
                            " - " + e.getMessage());
                } catch (Exception e) {
                    System.out.println("Error processing index: " + indexDir.getPath());
                    e.printStackTrace();
                }
            }

            System.out.println();
            System.out.println("All indices processed successfully!");

        } catch (Exception e) {
            System.out.println("Error in main execution:");
            e.printStackTrace();
        }
    }

    private static void exportStandardIndex(IndexReader reader, String indexName) throws IOException {
        System.out.println("Standard export for: " + indexName);
        // Get all field names
        Set<String> fieldNames = new HashSet<>();
        for (int i = 0; i < Math.min(1000, reader.maxDoc()); i++) {
            Document doc = reader.storedFields().document(i);
            for (IndexableField field : doc.getFields()) {
                fieldNames.add(field.name());
            }
        }
        List<String> fieldNameList = new ArrayList<>(fieldNames);
        String outputFile = OUTPUT_DIR + File.separator + indexName + ".csv";
        int totalDocs = reader.maxDoc();
        System.out.println("Exporting " + totalDocs + " documents...");
        try (OutputStreamWriter osw = new OutputStreamWriter(new FileOutputStream(outputFile), StandardCharsets.UTF_8);
                PrintWriter pw = new PrintWriter(osw)) {
            // Write header
            pw.println("DocID," + String.join(",", fieldNameList));
            // Write data without verbose reporting
            for (int i = 0; i < totalDocs; i++) {
                Document doc = reader.storedFields().document(i);
                StringBuilder sb = new StringBuilder();
                // Add DocID
                sb.append(i);
                // Add fields in order
                for (String fieldName : fieldNameList) {
                    sb.append(",");
                    String[] values = doc.getValues(fieldName);
                    if (values != null && values.length > 0) {
                        // Handle multi-valued fields by joining with semicolon
                        StringJoiner joiner = new StringJoiner(";");
                        for (String value : values) {
                            // Escape quotes in CSV
                            joiner.add(escapeCSV(value));
                        }
                        sb.append("\"").append(joiner.toString()).append("\"");
                    }
                }
                pw.println(sb.toString());
            }
        }
        System.out.println("Exported " + totalDocs + " documents to " + outputFile);
    }

    private static void exportBookIndexToSeparateFiles(IndexReader reader, String indexName) throws IOException {
        System.out.println("Exporting books to individual files");
        // Get all field names
        Set<String> fieldNames = new HashSet<>();
        for (int i = 0; i < Math.min(1000, reader.maxDoc()); i++) {
            Document doc = reader.storedFields().document(i);
            for (IndexableField field : doc.getFields()) {
                fieldNames.add(field.name());
            }
        }
        List<String> fieldNameList = new ArrayList<>(fieldNames);
        // Get unique book IDs efficiently using TermsEnum
        Set<String> bookIds = new HashSet<>();
        Terms terms = MultiTerms.getTerms(reader, "id");
        if (terms != null) {
            TermsEnum termsEnum = terms.iterator();
            BytesRef term;
            while ((term = termsEnum.next()) != null) {
                String idTerm = term.utf8ToString();
                if (idTerm.contains("-")) {
                    String bookId = idTerm.split("-")[0];
                    bookIds.add(bookId);
                }
            }
        }
        System.out.println("Found " + bookIds.size() + " unique book IDs");
        // Process one book at a time
        List<String> bookIdList = new ArrayList<>(bookIds);
        Collections.sort(bookIdList, Comparator.comparingInt(Integer::parseInt));
        // Create output folder for book CSVs
        String booksFolder = OUTPUT_DIR + File.separator + BOOK_OUTPUT_DIR;
        new File(booksFolder).mkdirs();
        // Process books in sorted order
        for (String bookId : bookIdList) {
            System.out.println("Processing book: " + bookId);
            
            // In test mode, process only the first book
            if (TEST_MODE && !bookId.equals(bookIdList.get(0))) {
                System.out.println("TEST MODE: Skipping book " + bookId + " (processing only first book)");
                continue;
            }
            // Create output file for this book
            String bookFile = booksFolder + File.separator + bookId + ".csv";
            try (OutputStreamWriter osw = new OutputStreamWriter(new FileOutputStream(bookFile), StandardCharsets.UTF_8);
                    PrintWriter pw = new PrintWriter(osw)) {
                // Write header
                pw.println("DocID,BookID,PageID," + String.join(",", fieldNameList));
                // Find all docs for this book_id
                List<DocWithId> docsForBook = new ArrayList<>();

                // Get leaf readers for checking live docs
                List<LeafReaderContext> leaves = reader.leaves();

                // Use TermEnum to find matching documents efficiently
                TermsEnum termsEnum = terms.iterator();
                BytesRef prefix = new BytesRef(bookId + "-");

                // Fix: Modified approach to handle the first entry
                termsEnum.seekCeil(prefix);

                // Process all terms that match our prefix
                BytesRef currentTerm = termsEnum.term();
                if (currentTerm != null && currentTerm.utf8ToString().startsWith(bookId + "-")) {
                    // Process the first matching term
                    processTermForBook(reader, termsEnum, bookId, docsForBook, leaves);

                    // Process remaining terms
                    while (true) {
                        BytesRef nextTerm = termsEnum.next();
                        if (nextTerm == null || !nextTerm.utf8ToString().startsWith(bookId + "-")) {
                            break;
                        }
                        processTermForBook(reader, termsEnum, bookId, docsForBook, leaves);
                    }
                }

                // Sort by page number
                Collections.sort(docsForBook);

                // Write this book's documents to the file
                for (DocWithId docWithId : docsForBook) {
                    Document doc = docWithId.getDoc();
                    int docId = docWithId.getDocId();
                    StringBuilder sb = new StringBuilder();
                    // Add DocID, BookID, PageID
                    sb.append(docId).append(",");
                    sb.append(bookId).append(",");
                    sb.append(docWithId.getPageId());
                    // Add fields in order
                    for (String fieldName : fieldNameList) {
                        sb.append(",");
                        String[] values = doc.getValues(fieldName);
                        if (values != null && values.length > 0) {
                            // Handle multi-valued fields
                            StringJoiner joiner = new StringJoiner(";");
                            for (String value : values) {
                                joiner.add(escapeCSV(value));
                            }
                            sb.append("\"").append(joiner.toString()).append("\"");
                        }
                    }
                    pw.println(sb.toString());
                }
                System.out.println("Exported book " + bookId + " with " + docsForBook.size() + " pages");
            }
        }
        System.out.println("Finished exporting all books to individual files");
    }

    // Helper method to process a term for a book
    // Uses liveDocs to skip deleted documents
    private static void processTermForBook(IndexReader reader, TermsEnum termsEnum, String bookId,
            List<DocWithId> docsForBook, List<LeafReaderContext> leaves) throws IOException {
        PostingsEnum postings = termsEnum.postings(null);
        while (postings.nextDoc() != PostingsEnum.NO_MORE_DOCS) {
            int docId = postings.docID();

            // Check if document is live (not deleted)
            int leafIdx = ReaderUtil.subIndex(docId, leaves);
            LeafReaderContext ctx = leaves.get(leafIdx);
            Bits liveDocs = ctx.reader().getLiveDocs();
            int localDocId = docId - ctx.docBase;

            // Skip deleted documents
            if (liveDocs != null && !liveDocs.get(localDocId)) {
                continue;
            }

            Document doc = reader.storedFields().document(docId);
            String idValue = doc.get("id");
            if (idValue != null && idValue.startsWith(bookId + "-")) {
                String pageId = idValue.split("-")[1];
                docsForBook.add(new DocWithId(docId, doc, Integer.parseInt(pageId)));
            }
        }
    }

    private static void exportTitleIndexToSeparateFiles(IndexReader reader, String indexName) throws IOException {
        System.out.println("Exporting title index to individual book files");

        // Get unique book IDs efficiently using TermsEnum
        Set<String> bookIds = new HashSet<>();
        Terms terms = MultiTerms.getTerms(reader, "id");
        if (terms != null) {
            TermsEnum termsEnum = terms.iterator();
            BytesRef term;
            while ((term = termsEnum.next()) != null) {
                String idTerm = term.utf8ToString();
                if (idTerm.contains("-")) {
                    String bookId = idTerm.split("-")[0];
                    bookIds.add(bookId);
                }
            }
        }

        System.out.println("Found " + bookIds.size() + " unique book IDs in titles");

        // Create output folder for title CSVs
        String titlesFolder = OUTPUT_DIR + File.separator + TITLE_OUTPUT_DIR;
        new File(titlesFolder).mkdirs();

        // Process one book at a time
        List<String> bookIdList = new ArrayList<>(bookIds);
        Collections.sort(bookIdList, Comparator.comparingInt(Integer::parseInt));

        for (String bookId : bookIdList) {
            System.out.println("Processing titles for book: " + bookId);
            
            // In test mode, process only the first book
            if (TEST_MODE && !bookId.equals(bookIdList.get(0))) {
                System.out.println("TEST MODE: Skipping titles for book " + bookId + " (processing only first book)");
                continue;
            }

            // Create output file for this book's titles
            String titleFile = titlesFolder + File.separator + "title_" + bookId + ".csv";

            try (OutputStreamWriter osw = new OutputStreamWriter(new FileOutputStream(titleFile), StandardCharsets.UTF_8);
                    PrintWriter pw = new PrintWriter(osw)) {

                // Write header (simplified as per requirement)
                pw.println("DocID,id,body");

                // Find all titles for this book
                List<TitleDoc> titlesForBook = new ArrayList<>();

                // Get leaf readers for checking live docs
                List<LeafReaderContext> leaves = reader.leaves();

                // Use TermEnum to find matching documents efficiently
                TermsEnum termsEnum = terms.iterator();
                BytesRef prefix = new BytesRef(bookId + "-");

                // Fix: Modified approach to handle the first entry
                termsEnum.seekCeil(prefix);

                // Process all terms that match our prefix
                BytesRef currentTerm = termsEnum.term();
                if (currentTerm != null && currentTerm.utf8ToString().startsWith(bookId + "-")) {
                    // Process the first matching term
                    processTermForTitle(reader, termsEnum, bookId, titlesForBook, leaves);

                    // Process remaining terms
                    while (true) {
                        BytesRef nextTerm = termsEnum.next();
                        if (nextTerm == null || !nextTerm.utf8ToString().startsWith(bookId + "-")) {
                            break;
                        }
                        processTermForTitle(reader, termsEnum, bookId, titlesForBook, leaves);
                    }
                }

                // Sort by title ID within this book
                Collections.sort(titlesForBook, Comparator.comparingInt(TitleDoc::getTitleId));

                // Write this book's titles to the file
                for (TitleDoc titleDoc : titlesForBook) {
                    Document doc = titleDoc.getDoc();
                    int docId = titleDoc.getOrigDocId();
                    String idValue = doc.get("id");
                    String bodyValue = doc.get("body");

                    // Write DocID, id, body as requested
                    pw.println(docId + ",\"" + escapeCSV(idValue) + "\",\"" + escapeCSV(bodyValue) + "\"");
                }

                System.out.println("Exported " + titlesForBook.size() + " titles for book " + bookId);
            }
        }

        System.out.println("Finished exporting all titles to individual files");
    }

    // Helper method to process a term for a title
    // Uses liveDocs to skip deleted documents
    private static void processTermForTitle(IndexReader reader, TermsEnum termsEnum, String bookId,
            List<TitleDoc> titlesForBook, List<LeafReaderContext> leaves) throws IOException {
        PostingsEnum postings = termsEnum.postings(null);
        while (postings.nextDoc() != PostingsEnum.NO_MORE_DOCS) {
            int docId = postings.docID();

            // Check if document is live (not deleted)
            int leafIdx = ReaderUtil.subIndex(docId, leaves);
            LeafReaderContext ctx = leaves.get(leafIdx);
            Bits liveDocs = ctx.reader().getLiveDocs();
            int localDocId = docId - ctx.docBase;

            // Skip deleted documents
            if (liveDocs != null && !liveDocs.get(localDocId)) {
                continue;
            }

            Document doc = reader.storedFields().document(docId);
            String idValue = doc.get("id");

            if (idValue != null && idValue.startsWith(bookId + "-")) {
                String titleIdStr = idValue.split("-")[1];
                int titleId = Integer.parseInt(titleIdStr);
                titlesForBook.add(new TitleDoc(docId, doc, Integer.parseInt(bookId), titleId));
            }
        }
    }

    private static String escapeCSV(String value) {
        if (value == null)
            return "";
        return value.replace("\"", "\"\"");
    }

    // Helper class to hold doc info and sort by page ID
    static class DocWithId implements Comparable<DocWithId> {
        private final int docId;
        private final Document doc;
        private final int pageId;

        public DocWithId(int docId, Document doc, int pageId) {
            this.docId = docId;
            this.doc = doc;
            this.pageId = pageId;
        }

        public int getDocId() {
            return docId;
        }

        public Document getDoc() {
            return doc;
        }

        public int getPageId() {
            return pageId;
        }

        @Override
        public int compareTo(DocWithId other) {
            return Integer.compare(this.pageId, other.pageId);
        }
    }

    // Helper class for title documents
    static class TitleDoc implements Comparable<TitleDoc> {
        private final int origDocId;
        private final Document doc;
        private final int bookId;
        private final int titleId;

        public TitleDoc(int origDocId, Document doc, int bookId, int titleId) {
            this.origDocId = origDocId;
            this.doc = doc;
            this.bookId = bookId;
            this.titleId = titleId;
        }

        public int getOrigDocId() {
            return origDocId;
        }

        public Document getDoc() {
            return doc;
        }

        public int getBookId() {
            return bookId;
        }

        public int getTitleId() {
            return titleId;
        }

        @Override
        public int compareTo(TitleDoc other) {
            // First sort by book ID
            int bookCompare = Integer.compare(this.bookId, other.bookId);
            if (bookCompare != 0) {
                return bookCompare;
            }

            // Then sort by title ID
            return Integer.compare(this.titleId, other.titleId);
        }
    }
}