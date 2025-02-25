package managers;

import akka.util.Timeout;
import com.datastax.driver.core.Row;
import com.google.gson.Gson;
import commons.AppConfig;
import commons.DialCodeErrorCodes;
import commons.DialCodeErrorMessage;
import commons.dto.HeaderParam;
import commons.dto.Response;
import commons.exception.ClientException;
import commons.exception.ResponseCode;
import dbstore.DialCodeStore;
import dbstore.PublisherStore;
import dto.DialCode;
import dto.Publisher;
import dto.SearchDTO;
import elasticsearch.ElasticSearchUtil;
import elasticsearch.SearchProcessor;
import org.apache.commons.lang3.StringUtils;
import org.elasticsearch.action.search.SearchResponse;
import scala.concurrent.Await;
import scala.concurrent.Future;
import scala.concurrent.duration.Duration;
import telemetry.TelemetryManager;
import utils.Constants;
import utils.DateUtils;
import utils.DialCodeEnum;
import utils.DialCodeGenerator;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class DialcodeManager extends BaseManager {
    private PublisherStore publisherStore = new PublisherStore();

    private DialCodeStore dialCodeStore = new DialCodeStore();

    private DialCodeGenerator dialCodeGenerator =  new DialCodeGenerator();

    public static Timeout WAIT_TIMEOUT = new Timeout(Duration.create(30, TimeUnit.SECONDS));

    private int defaultLimit = 1000;
    private int defaultOffset = 0;
    private String connectionInfo = "localhost:9300";
    private SearchProcessor processor = null;

    public SearchProcessor getProcessor() {
        return processor;
    }

    public void setProcessor(SearchProcessor processor) {
        this.processor = processor;
    }

    public DialcodeManager(){
        init();
    }

    public void init() {
        defaultLimit = AppConfig.config.hasPath(Constants.DIALCODE_SEARCH_LIMIT)
                ? AppConfig.config.getInt(Constants.DIALCODE_SEARCH_LIMIT) : defaultLimit;
        connectionInfo = AppConfig.config.hasPath(Constants.DIALCODE_ES_CONN_INFO)
                ? AppConfig.config.getString(Constants.DIALCODE_ES_CONN_INFO)
                : connectionInfo;

        //ElasticSearchUtil.initialiseESClient(Constants.DIAL_CODE_INDEX, connectionInfo);
        processor = new SearchProcessor();

    }

    /*
     * (non-Javadoc)
     *
     * @see
     * org.ekstep.dialcode.mgr.IDialCodeManager#generateDialCode(java.utils.Map,
     * java.lang.String)
     */
    public Response generateDialCode(Map<String, Object> map, String channelId) throws Exception {
        Map<Double, String> dialCodeMap;
        if (null == map)
            return ERROR(DialCodeErrorCodes.ERR_INVALID_DIALCODE_REQUEST,
                    DialCodeErrorMessage.ERR_INVALID_DIALCODE_REQUEST, ResponseCode.CLIENT_ERROR);
        String publisher = (String) map.get(DialCodeEnum.publisher.name());
        validatePublisher(publisher);
        int userCount = getCount(map);
        Integer maxCount = AppConfig.config.getInt("dialcode.max_count");
        String batchCode = (String) map.get(DialCodeEnum.batchCode.name());
        if (StringUtils.isBlank(batchCode)) {
            batchCode = generateBatchCode(publisher);
            map.put(DialCodeEnum.batchCode.name(), batchCode);
        }
        Response resp = null;
        if (userCount > maxCount) {
            dialCodeMap = dialCodeGenerator.generate(maxCount, channelId, publisher, batchCode);
            resp = getPartialSuccessResponse();
        } else {
            dialCodeMap = dialCodeGenerator.generate(userCount, channelId, publisher, batchCode);
            resp = getSuccessResponse();
        }

        resp.put(DialCodeEnum.count.name(), dialCodeMap.size());
        resp.put(DialCodeEnum.batchcode.name(), batchCode);
        resp.put(DialCodeEnum.publisher.name(), publisher);
        resp.put(DialCodeEnum.dialcodes.name(), dialCodeMap.values());
        TelemetryManager.info("DIAL codes generated", resp.getResult());
        return resp;
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * org.ekstep.dialcode.mgr.IDialCodeManager#readDialCode(java.lang.String)
     */
    public Response readDialCode(String dialCodeId) throws Exception {
        if (StringUtils.isBlank(dialCodeId))
            return ERROR(DialCodeErrorCodes.ERR_INVALID_DIALCODE_REQUEST,
                    DialCodeErrorMessage.ERR_INVALID_DIALCODE_REQUEST, ResponseCode.CLIENT_ERROR);
        DialCode dialCode = dialCodeStore.read(dialCodeId);
        Response resp = getSuccessResponse();
        resp.put(DialCodeEnum.dialcode.name(), dialCode);
        return resp;
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * org.ekstep.dialcode.mgr.IDialCodeManager#updateDialCode(java.lang.String,
     * java.lang.String, java.utils.Map)
     */
    public Response updateDialCode(String dialCodeId, String channelId, Map<String, Object> map) throws Exception {
        if (null == map)
            return ERROR(DialCodeErrorCodes.ERR_INVALID_DIALCODE_REQUEST,
                    DialCodeErrorMessage.ERR_INVALID_DIALCODE_REQUEST, ResponseCode.CLIENT_ERROR);
        DialCode dialCode = dialCodeStore.read(dialCodeId);
        if (!channelId.equalsIgnoreCase(dialCode.getChannel()))
            return ERROR(DialCodeErrorCodes.ERR_INVALID_CHANNEL_ID, DialCodeErrorMessage.ERR_INVALID_CHANNEL_ID,
                    ResponseCode.CLIENT_ERROR);
        if (dialCode.getStatus().equalsIgnoreCase(DialCodeEnum.Live.name()))
            return ERROR(DialCodeErrorCodes.ERR_DIALCODE_UPDATE, DialCodeErrorMessage.ERR_DIALCODE_UPDATE,
                    ResponseCode.CLIENT_ERROR);
        String metaData = new Gson().toJson(map.get(DialCodeEnum.metadata.name()));
        Map<String, Object> data = new HashMap<String, Object>();
        data.put(DialCodeEnum.metadata.name(), metaData);
        dialCodeStore.update(dialCodeId, data, null);
        Response resp = getSuccessResponse();
        resp.put(DialCodeEnum.identifier.name(), dialCode.getIdentifier());
        TelemetryManager.info("DIAL code updated", resp.getResult());
        return resp;
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * org.ekstep.dialcode.mgr.IDialCodeManager#listDialCode(java.lang.String,
     * java.utils.Map)
     */
    public Response listDialCode(Map<String, Object> requestContext, Map<String, Object> map) throws Exception {
        if (null == map)
            return ERROR(DialCodeErrorCodes.ERR_INVALID_SEARCH_REQUEST, DialCodeErrorMessage.ERR_INVALID_SEARCH_REQUEST,
                    ResponseCode.CLIENT_ERROR);
        return searchDialCode(requestContext, map);
    }



    /*
     * (non-Javadoc)
     *
     * @see
     * org.ekstep.dialcode.mgr.IDialCodeManager#listDialCode(java.lang.String,
     * java.utils.Map)
     */
    public Response searchDialCode(Map<String, Object> requestContext, Map<String, Object> map) throws Exception {
        if (null == map)
            return ERROR(DialCodeErrorCodes.ERR_INVALID_SEARCH_REQUEST, DialCodeErrorMessage.ERR_INVALID_SEARCH_REQUEST,
                    ResponseCode.CLIENT_ERROR);
        int limit = getLimit(map, DialCodeErrorCodes.ERR_INVALID_SEARCH_REQUEST);
        int offset = getOffset(map, DialCodeErrorCodes.ERR_INVALID_SEARCH_REQUEST);
        map.remove("limit");
        map.remove("offset");
        Map<String, Object> dialCodeSearch = searchDialCodes(requestContext, map, limit, offset);

        Response resp = getSuccessResponse();
        resp.put(DialCodeEnum.count.name(), dialCodeSearch.get(DialCodeEnum.count.name()));
        resp.put(DialCodeEnum.dialcodes.name(), dialCodeSearch.get(DialCodeEnum.dialcodes.name()));
        return resp;
    }

    /**
     * @param map
     * @param errCode
     * @return
     */
    private int getOffset(Map<String, Object> map, String errCode) {
        int offset = defaultOffset;
        try {
            if (map.containsKey("offset"))
                offset = ((Number) map.get("offset")).intValue();
        } catch (Exception e) {
            throw new ClientException(errCode, "Please provide valid offset.");
        }
        return offset;
    }

    private int getLimit(Map<String, Object> map, String errCode) {
        int limit = defaultLimit;
        try {
            if (map.containsKey("limit"))
                limit = ((Number) map.get("limit")).intValue();
        } catch (Exception e) {
            throw new ClientException(errCode, "Please provide valid limit.");
        }
        return limit;
    }

    /*
     * (non-Javadoc)
     *
     * @see org.ekstep.dialcode.mgr.IDialCodeManager#publishDialCode(java.lang.
     * String, java.lang.String)
     */
    public Response publishDialCode(String dialCodeId, String channelId) throws Exception {
        Response resp = null;
        DialCode dialCode = dialCodeStore.read(dialCodeId);
        if (!channelId.equalsIgnoreCase(dialCode.getChannel()))
            return ERROR(DialCodeErrorCodes.ERR_INVALID_CHANNEL_ID, DialCodeErrorMessage.ERR_INVALID_CHANNEL_ID,
                    ResponseCode.CLIENT_ERROR);
        Map<String, Object> data = new HashMap<String, Object>();
        String publishedOn = DateUtils.formatCurrentDate();
        data.put(DialCodeEnum.status.name(), DialCodeEnum.Live.name());
        data.put(DialCodeEnum.published_on.name(), publishedOn);
        Map<String, Object> statusChangedOnMap = new HashMap<String, Object>() {{
            put("ov", dialCode.getGeneratedOn());
            put("nv", publishedOn);
        }};
        Map<String, Object> extEventData = new HashMap<String, Object>() {{
            put("lastStatusChangedOn", statusChangedOnMap);
        }};

        dialCodeStore.update(dialCodeId, data, extEventData);
        resp = getSuccessResponse();
        resp.put(DialCodeEnum.identifier.name(), dialCode.getIdentifier());
        TelemetryManager.info("DIAL code published", resp.getResult());
        return resp;
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * org.ekstep.dialcode.mgr.IDialCodeManager#syncDialCode(java.lang.String,
     * java.utils.Map, java.utils.List)
     */
    public Response syncDialCode(String channelId, Map<String, Object> map, List<String> identifiers) throws Exception {
        Map<String, Object> requestMap = new HashMap<String, Object>();
        if ((null == identifiers || identifiers.isEmpty()) && (null == map || map.isEmpty())) {
            return ERROR(DialCodeErrorCodes.ERR_INVALID_SYNC_REQUEST, DialCodeErrorMessage.ERR_INVALID_SYNC_REQUEST,
                    ResponseCode.CLIENT_ERROR);
        }
        requestMap.putAll(map);
        if (null != identifiers && !identifiers.isEmpty()) {
            requestMap.put(DialCodeEnum.identifier.name(), identifiers);
        }

        if (requestMap.isEmpty()) {
            throw new ClientException(DialCodeErrorCodes.ERR_INVALID_SYNC_REQUEST,
                    "Either batchCode or atleat one identifier is mandatory");
        }
        int rowsSynced = dialCodeStore.sync(requestMap);
        Response response = getSuccessResponse();
        response.put(DialCodeEnum.count.name(), rowsSynced);
        TelemetryManager.info("DIAL code are successfully synced", response.getResult());
        return response;
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * org.ekstep.dialcode.mgr.IDialCodeManager#createPublisher(java.utils.Map,
     * java.lang.String)
     */
    public Response createPublisher(Map<String, Object> map, String channelId) throws Exception {
        String ERROR_CODE = "ERR_INVALID_PUBLISHER_CREATION_REQUEST";
        if (null == map)
            return ERROR(ERROR_CODE, "Invalid Request", ResponseCode.CLIENT_ERROR);

        if (!map.containsKey(DialCodeEnum.identifier.name())
                || StringUtils.isBlank((String) map.get(DialCodeEnum.identifier.name()))) {
            return ERROR(ERROR_CODE, "Invalid Publisher Identifier", ResponseCode.CLIENT_ERROR);
        }

        if (!map.containsKey(DialCodeEnum.name.name())
                || StringUtils.isBlank((String) map.get(DialCodeEnum.name.name()))) {
            return ERROR(ERROR_CODE, "Invalid Publisher Name", ResponseCode.CLIENT_ERROR);
        }
        String identifier = (String) map.get(DialCodeEnum.identifier.name());
        List<Row> listOfPublisher = publisherStore.get(DialCodeEnum.identifier.name(), identifier);

        if (!listOfPublisher.isEmpty())
            return ERROR(ERROR_CODE, "Publisher with identifier: " + identifier + " already exists.",
                    ResponseCode.CLIENT_ERROR);

        Map<String, Object> publisherMap = getPublisherMap(map, channelId, true);
        publisherStore.create(identifier, publisherMap);

        Response response = new Response();
        response.put(DialCodeEnum.identifier.name(), identifier);
        return response;
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * org.ekstep.dialcode.mgr.IDialCodeManager#readPublisher(java.lang.String)
     */
    public Response readPublisher(String publisherId) throws Exception {
        String ERROR_CODE = "ERR_INVALID_PUBLISHER_READ_REQUEST";
        if (StringUtils.isBlank(publisherId))
            return ERROR(ERROR_CODE, "Invalid Publisher Identifier", ResponseCode.CLIENT_ERROR);

        List<Row> listOfPublisher = publisherStore.get(DialCodeEnum.identifier.name(), publisherId);
        if (listOfPublisher.isEmpty())
            return ERROR(ERROR_CODE, "Publisher with Identifier: " + publisherId + " does not exists.",
                    ResponseCode.CLIENT_ERROR);

        Row publisherRow = listOfPublisher.get(0);
        Publisher publisher = new Publisher(publisherRow.getString(DialCodeEnum.identifier.name()),
                publisherRow.getString(DialCodeEnum.name.name()), publisherRow.getString(DialCodeEnum.channel.name()),
                publisherRow.getString(DialCodeEnum.created_on.name()),
                publisherRow.getString(DialCodeEnum.updated_on.name()));

        Response response = new Response();
        response.put(DialCodeEnum.publisher.name(), publisher);
        return response;
    }

    /*
     * (non-Javadoc)
     *
     * @see org.ekstep.dialcode.mgr.IDialCodeManager#updatePublisher(java.lang.
     * String, java.lang.String, java.utils.Map)
     */
    public Response updatePublisher(String publisherId, String channelId, Map<String, Object> map) throws Exception {
        String ERROR_CODE = "ERR_INVALID_PUBLISHER_UPDATE_REQUEST";
        if (null == map)
            return ERROR(ERROR_CODE, "Invalid Request", ResponseCode.CLIENT_ERROR);

        List<Row> listOfPublisher = publisherStore.get(DialCodeEnum.identifier.name(), publisherId);

        if (listOfPublisher.isEmpty())
            return ERROR(ERROR_CODE, "Publisher with Identifier: " + publisherId + " does not exists.",
                    ResponseCode.CLIENT_ERROR);

        Map<String, Object> publisherMap = getPublisherMap(map, channelId, false);
        publisherStore.modify(DialCodeEnum.identifier.name(), publisherId, publisherMap);

        Response response = new Response();
        response.put(DialCodeEnum.identifier.name(), publisherId);
        return response;
    }

    private Map<String, Object> getPublisherMap(Map<String, Object> map, String channel, boolean isCreateOperation) {
        Map<String, Object> publisherMap = new HashMap<>();
        if (isCreateOperation) {
            publisherMap.put(DialCodeEnum.identifier.name(), map.get(DialCodeEnum.identifier.name()));
            publisherMap.put(DialCodeEnum.channel.name(), channel);
            publisherMap.put(DialCodeEnum.created_on.name(), LocalDateTime.now().toString());
        }
        if (map.containsKey(DialCodeEnum.name.name())
                && !StringUtils.isBlank((String) map.get(DialCodeEnum.name.name()))) {
            publisherMap.put(DialCodeEnum.name.name(), map.get(DialCodeEnum.name.name()));
        }
        publisherMap.put(DialCodeEnum.updated_on.name(), LocalDateTime.now().toString());

        return publisherMap;
    }

    /**
     * @param publisher
     * @return String
     */
    private String generateBatchCode(String publisher) {
        if(StringUtils.isNotBlank(publisher)){
            DateFormat df = new SimpleDateFormat("yyyyMMdd'T'HHmmss");
            String date = df.format(new Date());
            return publisher.concat(".").concat(date);
        }
        return null;
    }

    /**
     * @param map
     * @return Integer
     * @throws ClientException
     */
    private int getCount(Map<String, Object> map) throws ClientException {
        Integer count = 0;
        try {
            count = ((Number) map.get(DialCodeEnum.count.name())).intValue();
        } catch (Exception e) {
            throw new ClientException(DialCodeErrorCodes.ERR_INVALID_COUNT, DialCodeErrorMessage.ERR_INVALID_COUNT);
        }

        if (count <= 0) {
            throw new ClientException(DialCodeErrorCodes.ERR_COUNT_VALIDATION_FAILED,
                    "Please give valid count to generate.");
        } else {
            return count;
        }
    }

    // TODO: Enhance it for Specific Server Error Message.
    // TODO: Enhance DialCodeStoreUtil and Use it instead of calling
    // CassandraStoreUtil directly.
    private void validatePublisher(String publisherId) throws Exception {
        if(StringUtils.isNotBlank(publisherId)){
            String pubId = "";
            try {
                List<Row> list = publisherStore.get(DialCodeEnum.identifier.name(), publisherId);
                Row row = list.get(0);
                pubId = row.getString(DialCodeEnum.identifier.name());
            } catch (Exception e) {
                // TODO: Enhance it to Specific Error Code
            }
            if (!StringUtils.equals(publisherId, pubId))
                throw new ClientException(DialCodeErrorCodes.ERR_INVALID_PUBLISHER,
                        DialCodeErrorMessage.ERR_INVALID_PUBLISHER, ResponseCode.CLIENT_ERROR);
        }
    }

    /**
     * @param requestContext
     * @param map
     * @param offset
     * @return
     * @throws Exception
     */
    private Map<String, Object> searchDialCodes(Map<String, Object> requestContext, Map<String, Object> map, int limit, int offset)
            throws Exception {
        Map<String, Object> dialCodeSearch = new HashMap<String, Object>();
        List<Object> searchResult = new ArrayList<Object>();
        String channelId = (String)requestContext.getOrDefault(HeaderParam.CHANNEL_ID.name(),"");
        SearchDTO searchDto = new SearchDTO();
        searchDto.setFuzzySearch(false);

        searchDto.setProperties(setSearchProperties(channelId, map));
        searchDto.setOperation(Constants.SEARCH_OPERATION_AND);
        searchDto.setFields(getFields());
        searchDto.setLimit(limit);
        searchDto.setOffset(offset);

        Map<String, String> sortBy = new HashMap<String, String>();
        sortBy.put("dialcode_index", "asc");
        searchDto.setSortBy(sortBy);
        Future<SearchResponse> searchResp = processor.processSearchQueryWithSearchResult(searchDto, false,
                Constants.DIAL_CODE_INDEX, true);
        SearchResponse searchResponse = Await.result(searchResp, WAIT_TIMEOUT.duration());
        searchResult = ElasticSearchUtil.getDocumentsFromHits(searchResponse.getHits());
        dialCodeSearch.put(DialCodeEnum.count.name(), (int) searchResponse.getHits().getTotalHits());
        dialCodeSearch.put(DialCodeEnum.dialcodes.name(), searchResult);
        writeTelemetrySearchLog(requestContext, map, dialCodeSearch);
        return dialCodeSearch;
    }

    /**
     * @return
     */
    private List<String> getFields() {
        List<String> fields = new ArrayList<String>();
        fields.add("dialcode_index");
        fields.add("publisher");
        fields.add("generated_on");
        fields.add("batchcode");
        fields.add("channel");
        fields.add("status");
        fields.add("metadata");

        return fields;
    }

    /**
     * @param channelId
     * @param map
     * @return
     */
    @SuppressWarnings("rawtypes")
    private List<Map> setSearchProperties(String channelId, Map<String, Object> map) {
        List<Map> properties = new ArrayList<Map>();
        Map<String, Object> property = new HashMap<String, Object>();
        property.put("operation", Constants.SEARCH_OPERATION_EQUAL);
        property.put("propertyName", DialCodeEnum.channel.name());
        property.put("values", channelId);
        properties.add(property);

        property = new HashMap<String, Object>();
        property.put("operation", Constants.SEARCH_OPERATION_EQUAL);
        property.put("propertyName", DialCodeEnum.objectType.name());
        property.put("values", DialCodeEnum.DialCode.name());
        properties.add(property);

        for (String key : map.keySet()) {
            property = new HashMap<String, Object>();
            property.put("operation", Constants.SEARCH_OPERATION_EQUAL);
            property.put("propertyName", key);
            property.put("values", map.get(key));
            properties.add(property);
        }

        return properties;
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private void writeTelemetrySearchLog(Map<String, Object> requestContext, Map<String, Object> searchCriteria,
                                         Map<String, Object> dialCodeSearch) {

        String query = "";
        String type = DialCodeEnum.DialCode.name();
        Map sort = new HashMap();
        String channelId = (String)requestContext.get(HeaderParam.CHANNEL_ID.name());
        Map<String, Object> filters = new HashMap<String, Object>();
        filters.put(DialCodeEnum.objectType.name(), DialCodeEnum.DialCode.name());
        filters.put(DialCodeEnum.channel.name(), channelId);
        filters.putAll(searchCriteria);
        int count = (int) dialCodeSearch.get(DialCodeEnum.count.name());
        Object topN = getTopNResult((List<Object>) dialCodeSearch.get(DialCodeEnum.dialcodes.name()));
        TelemetryManager.search(requestContext, query, filters, sort, count, topN, type);

    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getTopNResult(List<Object> result) {
        if (null == result || result.isEmpty()) {
            return new ArrayList<>();
        }
        Integer topN = AppConfig.config.hasPath("telemetry.search.topn")
                ? AppConfig.config.getInt("telemetry.search.topn") : 5;
        List<Map<String, Object>> list = new ArrayList<>();
        if (topN < result.size()) {
            for (int i = 0; i < topN; i++) {
                Map<String, Object> m = new HashMap<>();
                m.put("identifier", ((Map<String, Object>) result.get(i)).get("identifier"));
                list.add(m);
            }
        } else {
            for (int i = 0; i < result.size(); i++) {
                Map<String, Object> m = new HashMap<>();
                m.put("identifier", ((Map<String, Object>) result.get(i)).get("identifier"));
                list.add(m);
            }
        }
        return list;
    }



}
