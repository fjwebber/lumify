package io.lumify.opennlpDictionary;

import com.google.inject.Inject;
import io.lumify.core.ingest.graphProperty.GraphPropertyWorkData;
import io.lumify.core.ingest.graphProperty.GraphPropertyWorker;
import io.lumify.core.ingest.graphProperty.GraphPropertyWorkerPrepareData;
import io.lumify.core.model.properties.LumifyProperties;
import io.lumify.core.model.termMention.TermMentionBuilder;
import io.lumify.core.util.LumifyLogger;
import io.lumify.core.util.LumifyLoggerFactory;
import io.lumify.opennlpDictionary.model.DictionaryEntry;
import io.lumify.opennlpDictionary.model.DictionaryEntryRepository;
import io.lumify.web.clientapi.model.VisibilityJson;
import opennlp.tools.dictionary.Dictionary;
import opennlp.tools.namefind.DictionaryNameFinder;
import opennlp.tools.namefind.TokenNameFinder;
import opennlp.tools.tokenize.Tokenizer;
import opennlp.tools.tokenize.TokenizerME;
import opennlp.tools.tokenize.TokenizerModel;
import opennlp.tools.util.ObjectStream;
import opennlp.tools.util.PlainTextByLineStream;
import opennlp.tools.util.Span;
import opennlp.tools.util.StringList;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.securegraph.Element;
import org.securegraph.Property;
import org.securegraph.Vertex;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class OpenNLPDictionaryExtractorGraphPropertyWorker extends GraphPropertyWorker {
    private static final LumifyLogger LOGGER = LumifyLoggerFactory.getLogger(OpenNLPDictionaryExtractorGraphPropertyWorker.class);
    public static final String PATH_PREFIX_CONFIG = "termextraction.opennlp.pathPrefix";
    private static final String DEFAULT_PATH_PREFIX = "/lumify/config/opennlp/";
    private static final int NEW_LINE_CHARACTER_LENGTH = 1;

    private List<TokenNameFinder> finders;
    private DictionaryEntryRepository dictionaryEntryRepository;
    private Tokenizer tokenizer;

    @Override
    public void prepare(GraphPropertyWorkerPrepareData workerPrepareData) throws Exception {
        super.prepare(workerPrepareData);

        dictionaryEntryRepository.initializeTable(workerPrepareData.getUser());

        String pathPrefix = (String) workerPrepareData.getStormConf().get(PATH_PREFIX_CONFIG);
        if (pathPrefix == null) {
            pathPrefix = DEFAULT_PATH_PREFIX;
        }
        this.tokenizer = loadTokenizer(pathPrefix, workerPrepareData.getHdfsFileSystem());
        this.finders = loadFinders();
    }

    @Override
    public void execute(InputStream in, GraphPropertyWorkData data) throws Exception {
        ObjectStream<String> untokenizedLineStream = new PlainTextByLineStream(new InputStreamReader(in));
        String line;
        int charOffset = 0;

        LOGGER.debug("Processing artifact content stream");
        Vertex sourceVertex = (Vertex) data.getElement();
        List<Vertex> termMentions = new ArrayList<Vertex>();
        while ((line = untokenizedLineStream.read()) != null) {
            termMentions.addAll(processLine(sourceVertex, data.getProperty().getKey(), line, charOffset, LumifyProperties.VISIBILITY_JSON.getPropertyValue(sourceVertex)));
            getGraph().flush();
            charOffset += line.length() + NEW_LINE_CHARACTER_LENGTH;
        }
        applyTermMentionFilters(sourceVertex, termMentions);

        untokenizedLineStream.close();
        LOGGER.debug("Stream processing completed");
    }

    private List<Vertex> processLine(Vertex sourceVertex, String propertyKey, String line, int charOffset, VisibilityJson visibilityJson) {
        List<Vertex> termMentions = new ArrayList<Vertex>();
        String tokenList[] = tokenizer.tokenize(line);
        Span[] tokenListPositions = tokenizer.tokenizePos(line);
        for (TokenNameFinder finder : finders) {
            Span[] foundSpans = finder.find(tokenList);
            for (Span span : foundSpans) {
                termMentions.add(createTermMention(sourceVertex, propertyKey, charOffset, span, tokenList, tokenListPositions, visibilityJson));
            }
            finder.clearAdaptiveData();
        }
        return termMentions;
    }

    private Vertex createTermMention(Vertex sourceVertex, String propertyKey, int charOffset, Span foundName, String[] tokens, Span[] tokenListPositions, VisibilityJson visibilityJson) {
        String name = Span.spansToStrings(new Span[]{foundName}, tokens)[0];
        int start = charOffset + tokenListPositions[foundName.getStart()].getStart();
        int end = charOffset + tokenListPositions[foundName.getEnd() - 1].getEnd();
        String type = foundName.getType();
        String ontologyClassUri = mapToOntologyIri(type);

        return new TermMentionBuilder()
                .sourceVertex(sourceVertex)
                .propertyKey(propertyKey)
                .start(start)
                .end(end)
                .title(name)
                .conceptIri(ontologyClassUri)
                .visibilityJson(visibilityJson)
                .process(getClass().getName())
                .save(getGraph(), getVisibilityTranslator(), getAuthorizations());
    }

    @Override
    public boolean isHandled(Element element, Property property) {
        if (property == null) {
            return false;
        }

        if (property.getName().equals(LumifyProperties.RAW.getPropertyName())) {
            return false;
        }

        String mimeType = (String) property.getMetadata().get(LumifyProperties.MIME_TYPE.getPropertyName());
        return !(mimeType == null || !mimeType.startsWith("text"));
    }

    protected List<TokenNameFinder> loadFinders() throws IOException {
        List<TokenNameFinder> finders = new ArrayList<TokenNameFinder>();
        for (Map.Entry<String, Dictionary> dictionaryEntry : getDictionaries().entrySet()) {
            finders.add(new DictionaryNameFinder(dictionaryEntry.getValue(), dictionaryEntry.getKey()));
        }
        return finders;
    }

    protected Tokenizer loadTokenizer(String pathPrefix, FileSystem fs) throws IOException {
        Path tokenizerHdfsPath = new Path(pathPrefix + "/en-token.bin");

        TokenizerModel tokenizerModel = null;
        InputStream tokenizerModelInputStream = fs.open(tokenizerHdfsPath);
        try {
            tokenizerModel = new TokenizerModel(tokenizerModelInputStream);
        } finally {
            tokenizerModelInputStream.close();
        }

        return new TokenizerME(tokenizerModel);
    }

    private Map<String, Dictionary> getDictionaries() {
        Map<String, Dictionary> dictionaries = new HashMap<String, Dictionary>();
        Iterable<DictionaryEntry> entries = dictionaryEntryRepository.findAll(getUser().getModelUserContext());
        for (DictionaryEntry entry : entries) {

            if (!dictionaries.containsKey(entry.getMetadata().getConcept())) {
                dictionaries.put(entry.getMetadata().getConcept(), new Dictionary());
            }

            dictionaries.get(entry.getMetadata().getConcept()).put(tokensToStringList(entry.getMetadata().getTokens()));
        }

        return dictionaries;
    }

    private StringList tokensToStringList(String tokens) {
        return new StringList(tokens.split(" "));
    }

    @Inject
    public void setDictionaryEntryRepository(DictionaryEntryRepository dictionaryEntryRepository) {
        this.dictionaryEntryRepository = dictionaryEntryRepository;
    }
}
