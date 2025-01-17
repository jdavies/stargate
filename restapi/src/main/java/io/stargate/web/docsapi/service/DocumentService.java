package io.stargate.web.docsapi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import io.stargate.auth.UnauthorizedException;
import io.stargate.db.datastore.ResultSet;
import io.stargate.db.datastore.Row;
import io.stargate.db.query.Predicate;
import io.stargate.db.query.builder.BuiltCondition;
import io.stargate.web.docsapi.dao.DocumentDB;
import io.stargate.web.docsapi.dao.Paginator;
import io.stargate.web.docsapi.exception.DocumentAPIErrorHandlingStrategy;
import io.stargate.web.docsapi.exception.DocumentAPIRequestException;
import io.stargate.web.docsapi.service.filter.FilterCondition;
import io.stargate.web.docsapi.service.filter.FilterOp;
import io.stargate.web.docsapi.service.filter.ListFilterCondition;
import io.stargate.web.docsapi.service.filter.SingleFilterCondition;
import io.stargate.web.docsapi.service.json.DeadLeafCollectorImpl;
import io.stargate.web.docsapi.service.json.DeadLeafCollectorNoOp;
import io.stargate.web.docsapi.service.json.JsonConverter;
import io.stargate.web.resources.Db;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.ws.rs.core.PathSegment;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.mutable.MutableObject;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.jsfr.json.JsonSurfer;
import org.jsfr.json.JsonSurferGson;
import org.jsfr.json.compiler.JsonPathCompiler;
import org.jsfr.json.path.JsonPath;
import org.jsfr.json.path.PathOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DocumentService {
  private static final Logger logger = LoggerFactory.getLogger(DocumentService.class);
  private static final Pattern PERIOD_PATTERN = Pattern.compile("\\.");
  private static final Splitter FORM_SPLITTER = Splitter.on('&');
  private static final Splitter PAIR_SPLITTER = Splitter.on('=');
  private static final Splitter PATH_SPLITTER = Splitter.on('/');

  private final JsonConverter jsonConverterService = new JsonConverter();

  @VisibleForTesting
  JsonConverter getJsonConverterService() {
    return jsonConverterService;
  }

  /*
   * Converts a JSON path string (e.g. "$.a.b.c[0]") into a JSON path string
   * that only uses square brackets to denote pathing (e.g. "$['a']['b']['c'][0]".
   * This is to allow escaping of certain characters, such as space, $, and @.
   */
  private String convertToBracketedPath(String path) {
    String[] parts = PERIOD_PATTERN.split(path);
    StringBuilder newPath = new StringBuilder();
    for (int i = 0; i < parts.length; i++) {
      String part = parts[i];
      if (part.startsWith("$") && i == 0) {
        newPath.append(part);
      } else {
        int indexOfBrace = part.indexOf('[');
        if (indexOfBrace < 0) {
          newPath.append("['").append(part).append("']");
        } else {
          String keyPart = part.substring(0, indexOfBrace);
          String arrayPart = part.substring(indexOfBrace);
          newPath.append("['").append(keyPart).append("']").append(arrayPart);
        }
      }
    }
    return newPath.toString();
  }

  private String leftPadTo6(String value) {
    return StringUtils.leftPad(value, 6, '0');
  }

  private String convertArrayPath(String path) {
    if (path.startsWith("[") && path.endsWith("]")) {
      String innerPath = path.substring(1, path.length() - 1);
      int idx = Integer.parseInt(innerPath);
      if (idx > DocumentDB.MAX_ARRAY_LENGTH - 1) {
        throw new DocumentAPIRequestException(
            String.format("Max array length of %s exceeded.", DocumentDB.MAX_ARRAY_LENGTH));
      }
      return "[" + leftPadTo6(innerPath) + "]";
    }
    return path;
  }

  private boolean isEmptyObject(Object v) {
    return v instanceof JsonElement
        && ((JsonElement) v).isJsonObject()
        && ((JsonObject) v).size() == 0;
  }

  private boolean isEmptyArray(Object v) {
    return v instanceof JsonElement
        && ((JsonElement) v).isJsonArray()
        && ((JsonArray) v).size() == 0;
  }

  /**
   * Transforms a JSON payload into a set of bind variables to send to Cassandra.
   *
   * @param surfer
   * @param db
   * @param path The path within the document that is being written to. If empty, writes to the root
   *     of the document.
   * @param key The name of the document that will be written
   * @param payload a JSON object, or a URL-encoded form with the relevant data in it
   * @param patching If this payload meant to be part of a PATCH request (this causes a small amount
   *     of extra validation if true)
   * @param isJson if the request had a content type of application/json, else it will be
   *     interpreted as a URL encoded form
   * @return The full bind variable list for the subsequent inserts, and all first-level keys, as an
   *     ImmutablePair.
   */
  private ImmutablePair<List<Object[]>, List<String>> shredPayload(
      JsonSurfer surfer,
      DocumentDB db,
      List<String> path,
      String key,
      String payload,
      boolean patching,
      boolean isJson) {
    String trimmed = payload.trim();
    if (isJson) {
      return shredJson(surfer, db, path, key, trimmed, patching);
    } else {
      return shredForm(db, path, key, trimmed, patching);
    }
  }

  private ImmutablePair<List<Object[]>, List<String>> shredJson(
      JsonSurfer surfer,
      DocumentDB db,
      List<String> path,
      String key,
      String jsonPayload,
      boolean patching) {
    List<Object[]> bindVariableList = new ArrayList<>();
    List<String> firstLevelKeys = new ArrayList<>();

    surfer
        .configBuilder()
        .bind(
            "$..*",
            (v, parsingContext) -> {
              String fieldName = parsingContext.getCurrentFieldName();
              if (fieldName != null && DocumentDB.containsIllegalChars(fieldName)) {
                throw new DocumentAPIRequestException(
                    String.format(
                        "The characters %s are not permitted in JSON field names, invalid field %s",
                        DocumentDB.getForbiddenCharactersMessage(), fieldName));
              }

              if (v instanceof JsonPrimitive
                  || v instanceof JsonNull
                  || isEmptyObject(v)
                  || isEmptyArray(v)) {
                JsonPath p =
                    JsonPathCompiler.compile(convertToBracketedPath(parsingContext.getJsonPath()));
                int i = path.size();
                Map<String, Object> bindMap = db.newBindMap(path);

                bindMap.put("key", key);

                Iterator<PathOperator> it = p.iterator();
                String leaf = null;
                while (it.hasNext()) {
                  if (i >= DocumentDB.MAX_DEPTH) {
                    throw new DocumentAPIRequestException(
                        String.format("Max depth of %s exceeded", DocumentDB.MAX_DEPTH));
                  }

                  PathOperator op = it.next();
                  String pv = op.toString();

                  if (pv.equals("$")) continue;

                  // pv always starts with a square brace because of the above conversion
                  String innerPath = pv.substring(1, pv.length() - 1);
                  boolean isArrayElement = op.getType() == PathOperator.Type.ARRAY;
                  if (isArrayElement) {
                    if (i == path.size() && patching) {
                      throw new DocumentAPIRequestException(
                          "A patch operation must be done with a JSON object, not an array.");
                    }

                    int idx = Integer.parseInt(innerPath);
                    if (idx > DocumentDB.MAX_ARRAY_LENGTH - 1) {
                      throw new DocumentAPIRequestException(
                          String.format(
                              "Max array length of %s exceeded.", DocumentDB.MAX_ARRAY_LENGTH));
                    }

                    // left-pad the array element to 6 characters
                    pv = "[" + leftPadTo6(innerPath) + "]";
                  } else if (i == path.size()) {
                    firstLevelKeys.add(innerPath);
                    pv = innerPath;
                  } else {
                    pv = innerPath;
                  }

                  bindMap.put("p" + i++, pv);
                  leaf = pv;
                }

                bindMap.put("leaf", leaf);

                if (v instanceof JsonPrimitive) {
                  JsonPrimitive value = (JsonPrimitive) v;

                  if (value.isNumber()) {
                    bindMap.put("dbl_value", value.getAsDouble());
                    bindMap.put("bool_value", null);
                    bindMap.put("text_value", null);
                  } else if (value.isBoolean()) {
                    bindMap.put("dbl_value", null);
                    bindMap.put(
                        "bool_value",
                        convertToBackendBooleanValue(
                            value.getAsBoolean(), db.treatBooleansAsNumeric()));
                    bindMap.put("text_value", null);
                  } else {
                    bindMap.put("dbl_value", null);
                    bindMap.put("bool_value", null);
                    bindMap.put("text_value", value.getAsString());
                  }
                } else if (isEmptyObject(v)) {
                  bindMap.put("dbl_value", null);
                  bindMap.put("bool_value", null);
                  bindMap.put("text_value", DocumentDB.EMPTY_OBJECT_MARKER);
                } else if (isEmptyArray(v)) {
                  bindMap.put("dbl_value", null);
                  bindMap.put("bool_value", null);
                  bindMap.put("text_value", DocumentDB.EMPTY_ARRAY_MARKER);
                } else {
                  bindMap.put("dbl_value", null);
                  bindMap.put("bool_value", null);
                  bindMap.put("text_value", null);
                }

                logger.debug("{}", bindMap.values());
                bindVariableList.add(bindMap.values().toArray());
              }
            })
        .withErrorStrategy(new DocumentAPIErrorHandlingStrategy())
        .buildAndSurf(jsonPayload);
    return ImmutablePair.of(bindVariableList, firstLevelKeys);
  }

  private Object convertToBackendBooleanValue(boolean value, boolean numericBooleans) {
    if (numericBooleans) {
      return value ? 1 : 0;
    }
    return value;
  }

  private ImmutablePair<List<Object[]>, List<String>> shredForm(
      DocumentDB db, List<String> path, String key, String formPayload, boolean patching) {
    List<Object[]> bindVariableList = new ArrayList<>();
    List<String> firstLevelKeys = new ArrayList<>();
    Iterable<String> pairs = FORM_SPLITTER.split(formPayload);
    for (String pair : pairs) {
      List<String> data = PAIR_SPLITTER.splitToList(pair);
      String fullyQualifiedField;
      String value;
      if (data.size() == 2) {
        fullyQualifiedField = data.get(0);
        value = data.get(1);
      } else if (data.size() == 1) {
        fullyQualifiedField = "data";
        value = data.get(0);
      } else {
        continue;
      }
      String[] fieldNames = PERIOD_PATTERN.split(fullyQualifiedField);

      if (path.size() + fieldNames.length > DocumentDB.MAX_DEPTH) {
        throw new DocumentAPIRequestException(
            String.format("Max depth of %s exceeded", DocumentDB.MAX_DEPTH));
      }

      Map<String, Object> bindMap = db.newBindMap(path);
      bindMap.put("key", key);

      String leaf = null;
      for (int i = 0; i < fieldNames.length; i++) {
        String fieldName = fieldNames[i];
        boolean isArrayElement = fieldName.startsWith("[") && fieldName.endsWith("]");
        if (!isArrayElement) {
          // Unlike using JSON, try to allow any input by replacing illegal characters with _.
          // Form shredding is only supposed to be used for benchmarking tests.
          fieldName = DocumentDB.replaceIllegalChars(fieldName);
        }
        if (isArrayElement) {
          if (i == 0 && patching) {
            throw new DocumentAPIRequestException(
                "A patch operation must be done with a JSON object, not an array.");
          }

          String innerPath = fieldName.substring(1, fieldName.length() - 1);
          // Unlike using JSON, try to allow any input by replacing illegal characters with _.
          // Form shredding is only supposed to be used for benchmarking tests.
          innerPath = DocumentDB.replaceIllegalChars(innerPath);

          int idx = 0;
          try {
            idx = Integer.parseInt(innerPath);
          } catch (NumberFormatException e) {
            // do nothing
          }
          if (idx > DocumentDB.MAX_ARRAY_LENGTH - 1) {
            throw new DocumentAPIRequestException(
                String.format("Max array length of %s exceeded.", DocumentDB.MAX_ARRAY_LENGTH));
          }

          // left-pad the array element to 6 characters
          fieldName = "[" + leftPadTo6(innerPath) + "]";
        } else if (i == 0) {
          firstLevelKeys.add(fieldName);
        }

        bindMap.put("p" + (i + path.size()), fieldName);
        leaf = fieldName;
      }
      bindMap.put("leaf", leaf);

      if (value.equals("null")) {
        bindMap.put("dbl_value", null);
        bindMap.put("bool_value", null);
        bindMap.put("text_value", null);
      } else if (value.equals("true") || value.equals("false")) {
        bindMap.put("dbl_value", null);
        bindMap.put(
            "bool_value",
            convertToBackendBooleanValue(Boolean.parseBoolean(value), db.treatBooleansAsNumeric()));
        bindMap.put("text_value", null);
      } else {
        boolean isNumber;
        Double doubleValue = null;
        try {
          doubleValue = Double.parseDouble(value);
          isNumber = true;
        } catch (NumberFormatException e) {
          isNumber = false;
        }
        if (isNumber) {
          bindMap.put("dbl_value", doubleValue);
          bindMap.put("bool_value", null);
          bindMap.put("text_value", null);
        } else {
          bindMap.put("dbl_value", null);
          bindMap.put("bool_value", null);
          bindMap.put("text_value", value);
        }
      }
      logger.debug("{}", bindMap.values());
      bindVariableList.add(bindMap.values().toArray());
    }
    return ImmutablePair.of(bindVariableList, firstLevelKeys);
  }

  public void putAtPath(
      String authToken,
      String keyspace,
      String collection,
      String id,
      String payload,
      List<PathSegment> path,
      boolean patching,
      Db dbFactory,
      boolean isJson,
      Map<String, String> headers)
      throws UnauthorizedException {
    DocumentDB db = dbFactory.getDocDataStoreForToken(authToken, headers);

    JsonSurfer surfer = JsonSurferGson.INSTANCE;

    boolean created = db.maybeCreateTable(keyspace, collection);
    // After creating the table, it can take up to 2 seconds for permissions cache to be updated,
    // but we can force the permissions refetch by logging in again.
    if (created) {
      db = dbFactory.getDocDataStoreForToken(authToken, headers);
      db.maybeCreateTableIndexes(keyspace, collection);
    }

    // Left-pad the path segments that represent arrays
    List<String> convertedPath = new ArrayList<>(path.size());
    for (PathSegment pathSegment : path) {
      String pathStr = pathSegment.getPath();
      convertedPath.add(convertArrayPath(pathStr));
    }

    ImmutablePair<List<Object[]>, List<String>> shreddingResults =
        shredPayload(surfer, db, convertedPath, id, payload, patching, isJson);

    List<Object[]> bindVariableList = shreddingResults.left;
    List<String> firstLevelKeys = shreddingResults.right;

    if (bindVariableList.isEmpty() && isJson) {
      throw new DocumentAPIRequestException(
          "Updating a key with just a JSON primitive, empty object, or empty array is not allowed. Found: "
              + payload
              + ". Hint: update the parent path with a defined object instead.");
    }

    logger.debug("Bind {}", bindVariableList.size());

    long now = ChronoUnit.MICROS.between(Instant.EPOCH, Instant.now());
    if (patching) {
      db.deletePatchedPathsThenInsertBatch(
          keyspace, collection, id, bindVariableList, convertedPath, firstLevelKeys, now);
    } else {
      db.deleteThenInsertBatch(keyspace, collection, id, bindVariableList, convertedPath, now);
    }
  }

  public JsonNode getJsonAtPath(
      DocumentDB db, String keyspace, String collection, String id, List<PathSegment> path)
      throws UnauthorizedException {
    List<BuiltCondition> predicates = new ArrayList<>();
    predicates.add(BuiltCondition.of("key", Predicate.EQ, id));

    StringBuilder pathStr = new StringBuilder();

    for (int i = 0; i < path.size(); i++) {
      String pathSegment = path.get(i).getPath();
      String convertedPath = convertArrayPath(pathSegment);
      predicates.add(BuiltCondition.of("p" + i, Predicate.EQ, convertedPath));

      if (!pathSegment.equals(convertedPath)) {
        pathStr.append("/").append(pathSegment, 1, pathSegment.length() - 1);
      } else {
        pathStr.append("/").append(pathSegment);
      }
    }

    ResultSet r = db.executeSelect(keyspace, collection, predicates);
    List<Row> rows = r.rows();

    if (rows.isEmpty()) {
      return null;
    }
    DeadLeafCollectorImpl collector = new DeadLeafCollectorImpl();
    JsonNode result =
        getJsonConverterService()
            .convertToJsonDoc(rows, collector, false, db.treatBooleansAsNumeric());
    if (!collector.isEmpty()) {
      logger.info(String.format("Deleting %d dead leaves", collector.getLeaves().size()));
      db.deleteDeadLeaves(keyspace, collection, id, collector.getLeaves());
    }
    JsonNode node = result.at(pathStr.toString());
    if (node.isMissingNode()) {
      return null;
    }

    return node;
  }

  private void validateOpAndValue(String op, JsonNode value, String fieldName) {
    Optional<FilterOp> filterOpt = FilterOp.getByRawValue(op);
    // Further down the line, a nicer error message will be made if the filterOp is invalid
    if (!filterOpt.isPresent()) return;
    FilterOp filterOp = filterOpt.get();

    if (filterOp == FilterOp.NE) {
      if (value.isArray() || value.isObject()) {
        throw new DocumentAPIRequestException(
            String.format(
                "Value entry for field %s, operation %s was expecting a value or `null`",
                fieldName, op));
      }
    } else if (filterOp == FilterOp.EXISTS) {
      if (!value.isBoolean() || !value.asBoolean()) {
        throw new DocumentAPIRequestException(
            String.format("%s only supports the value `true`", op));
      }
    } else if (filterOp == FilterOp.GT
        || filterOp == FilterOp.GTE
        || filterOp == FilterOp.LT
        || filterOp == FilterOp.LTE
        || filterOp == FilterOp.EQ) {
      if (value.isArray() || value.isObject() || value.isNull()) {
        throw new DocumentAPIRequestException(
            String.format(
                "Value entry for field %s, operation %s was expecting a non-null value",
                fieldName, op));
      }
    } else if (filterOp == FilterOp.IN || filterOp == FilterOp.NIN) {
      if (!value.isArray()) {
        throw new DocumentAPIRequestException(
            String.format(
                "Value entry for field %s, operation %s was expecting an array", fieldName, op));
      }
    } else {
      throw new IllegalStateException(String.format("Unknown FilterOp value %s", filterOp));
    }
  }

  public List<String> convertToSelectionList(JsonNode fieldsJson) {
    if (!fieldsJson.isArray()) {
      throw new DocumentAPIRequestException(
          String.format("`fields` must be a JSON array, found %s", fieldsJson));
    }

    List<String> res = new ArrayList<>();
    for (int i = 0; i < fieldsJson.size(); i++) {
      JsonNode value = fieldsJson.get(i);
      if (!value.isTextual()) {
        throw new DocumentAPIRequestException(
            String.format("Each field must be a string, found %s", value));
      }
      res.add(value.asText());
    }

    return res;
  }

  public List<FilterCondition> convertToFilterOps(
      List<PathSegment> prependedPath, JsonNode filterJson) {
    List<FilterCondition> conditions = new ArrayList<>();

    if (!filterJson.isObject()) {
      throw new DocumentAPIRequestException("Search was expecting a JSON object as input.");
    }
    ObjectNode input = (ObjectNode) filterJson;
    Iterator<String> fields = input.fieldNames();
    while (fields.hasNext()) {
      String fieldName = fields.next();
      if (fieldName.isEmpty()) {
        throw new DocumentAPIRequestException(
            "The field(s) you are searching for can't be the empty string!");
      }
      String[] fieldNamePath = PERIOD_PATTERN.split(fieldName);
      List<String> convertedFieldNamePath =
          Arrays.asList(fieldNamePath).stream()
              .map(this::convertArrayPath)
              .collect(Collectors.toList());
      if (!prependedPath.isEmpty()) {
        List<String> prependedConverted =
            prependedPath.stream()
                .map(
                    pathSeg -> {
                      String path = pathSeg.getPath();
                      return convertArrayPath(path);
                    })
                .collect(Collectors.toList());
        prependedConverted.addAll(convertedFieldNamePath);
        convertedFieldNamePath = prependedConverted;
      }
      JsonNode fieldConditions = input.get(fieldName);
      if (!fieldConditions.isObject()) {
        throw new DocumentAPIRequestException(
            String.format(
                "Search entry for field %s was expecting a JSON object as input.", fieldName));
      }

      Iterator<String> ops = fieldConditions.fieldNames();
      while (ops.hasNext()) {
        String op = ops.next();
        JsonNode value = fieldConditions.get(op);
        validateOpAndValue(op, value, fieldName);
        if (value.isNumber()) {
          conditions.add(new SingleFilterCondition(convertedFieldNamePath, op, value.asDouble()));
        } else if (value.isBoolean()) {
          conditions.add(new SingleFilterCondition(convertedFieldNamePath, op, value.asBoolean()));
        } else if (value.isTextual()) {
          conditions.add(new SingleFilterCondition(convertedFieldNamePath, op, value.asText()));
        } else if (value.isArray()) {
          List<Object> valueAsList = new ArrayList<>();
          ArrayNode array = (ArrayNode) value;
          for (int i = 0; i < array.size(); i++) {
            JsonNode n = array.get(i);
            if (n.isInt()) valueAsList.add(n.asInt());
            else if (n.isDouble()) valueAsList.add(n.asDouble());
            else if (n.isBoolean()) valueAsList.add(n.asBoolean());
            else if (n.isTextual()) valueAsList.add(n.asText());
            else if (n.isNull()) valueAsList.add(null);
          }
          conditions.add(new ListFilterCondition(convertedFieldNamePath, op, valueAsList));
        } else {
          conditions.add(new SingleFilterCondition(convertedFieldNamePath, op, (String) null));
        }
      }
    }

    return conditions;
  }

  public void deleteAtPath(
      DocumentDB db, String keyspace, String collection, String id, List<PathSegment> path)
      throws UnauthorizedException {
    List<String> convertedPath = new ArrayList<>(path.size());
    for (PathSegment pathSegment : path) {
      String pathStr = pathSegment.getPath();
      convertedPath.add(convertArrayPath(pathStr));
    }
    Long now = ChronoUnit.MICROS.between(Instant.EPOCH, Instant.now());

    db.delete(keyspace, collection, id, convertedPath, now);
  }

  public JsonNode searchDocumentsV2(
      DocumentDB db,
      String keyspace,
      String collection,
      List<FilterCondition> filters,
      List<String> fields,
      String documentId,
      Paginator paginator)
      throws UnauthorizedException {
    FilterCondition first = filters.get(0);
    List<String> path = first.getPath();
    ObjectMapper mapper = getJsonConverterService().getMapper();

    List<Row> rows =
        searchRows(
            keyspace,
            collection,
            db,
            Collections.emptyList(),
            filters,
            fields,
            path,
            false,
            documentId,
            paginator);
    if (rows.isEmpty()) {
      return null;
    }

    JsonNode docsResult = mapper.createObjectNode();

    if (fields.isEmpty()) {
      Map<String, List<Row>> rowsByDoc = new HashMap<>();

      for (Row row : rows) {
        String key = row.getString("key");
        List<Row> rowsAtKey = rowsByDoc.getOrDefault(key, new ArrayList<>());
        rowsAtKey.add(row);
        rowsByDoc.put(key, rowsAtKey);
      }

      for (Map.Entry<String, List<Row>> entry : rowsByDoc.entrySet()) {
        ArrayNode ref = mapper.createArrayNode();
        if (documentId == null) {
          ((ObjectNode) docsResult).set(entry.getKey(), ref);
        } else {
          docsResult = ref;
        }
        List<Row> docRows = entry.getValue();
        for (Row row : docRows) {
          JsonNode jsonDoc =
              getJsonConverterService()
                  .convertToJsonDoc(
                      Collections.singletonList(row),
                      new DeadLeafCollectorNoOp(),
                      true,
                      db.treatBooleansAsNumeric());
          ref.add(jsonDoc);
        }
      }
    } else {
      if (documentId != null) {
        docsResult = mapper.createArrayNode();
      }
      for (List<Row> chunk : Lists.partition(rows, fields.size())) {
        String key = chunk.get(0).getString("key");
        if (!docsResult.has(key) && documentId == null) {
          ((ObjectNode) docsResult).set(key, mapper.createArrayNode());
        }

        List<Row> nonNull = chunk.stream().filter(x -> x != null).collect(Collectors.toList());
        JsonNode jsonDoc =
            getJsonConverterService()
                .convertToJsonDoc(
                    nonNull, new DeadLeafCollectorNoOp(), true, db.treatBooleansAsNumeric());

        if (documentId == null) {
          ((ArrayNode) docsResult.get(key)).add(jsonDoc);
        } else {
          ((ArrayNode) docsResult).add(jsonDoc);
        }
      }
    }

    if (docsResult.isMissingNode() || (docsResult.isObject() && docsResult.isEmpty())) {
      return null;
    }

    return docsResult;
  }

  @VisibleForTesting
  void addRowsToMap(Map<String, List<Row>> rowsByDoc, List<Row> rows) {
    for (Row row : rows) {
      String key = row.getString("key");
      List<Row> rowsAtKey = rowsByDoc.getOrDefault(key, new ArrayList<>());
      rowsAtKey.add(row);
      rowsByDoc.put(key, rowsAtKey);
    }
  }

  private List<Row> updateExistenceForMap(
      Set<String> existsByDoc,
      List<Row> rows,
      List<FilterCondition> filters,
      boolean booleansStoredAsTinyint,
      boolean endOfResults) {
    LinkedHashMap<String, List<Row>> documentChunks = new LinkedHashMap<>();
    for (int i = 0; i < rows.size(); i++) {
      Row row = rows.get(i);
      String key = row.getString("key");
      List<Row> chunk = documentChunks.getOrDefault(key, new ArrayList<>());
      chunk.add(row);
      documentChunks.put(key, chunk);
    }

    List<List<Row>> chunksList = new ArrayList<>(documentChunks.values());

    for (int i = 0; i < chunksList.size() - (endOfResults ? 0 : 1); i++) {
      List<Row> chunk = chunksList.get(i);
      String key = chunk.get(0).getString("key");
      List<Row> filteredRows =
          applyInMemoryFilters(chunk, filters, chunk.size(), booleansStoredAsTinyint);
      if (!filteredRows.isEmpty()) {
        existsByDoc.add(key);
      }
    }

    if (!chunksList.isEmpty() && !endOfResults) {
      return chunksList.get(chunksList.size() - 1);
    }
    return Collections.emptyList();
  }

  private boolean fieldMatchesRowPath(Row row, String field) {
    String rowPath = getFieldPathFromRow(row);
    return rowPath.startsWith(field)
        && (rowPath.substring(field.length()).isEmpty()
            || rowPath.substring(field.length()).startsWith("."));
  }

  /**
   * This method gets all the rows for @param Paginator#dbPageSize documents, by fetching result
   * sets sequentially and stringing them together. This is NOT expected to perform well for large
   * documents.
   */
  public JsonNode getFullDocuments(
      DocumentDB db, String keyspace, String collection, List<String> fields, Paginator paginator)
      throws UnauthorizedException {
    ObjectMapper mapper = getJsonConverterService().getMapper();
    ObjectNode docsResult = mapper.createObjectNode();
    LinkedHashMap<String, List<Row>> rowsByDoc = new LinkedHashMap<>();
    do {

      List<Row> rows =
          searchRows(
              keyspace,
              collection,
              db,
              new ArrayList<>(),
              new ArrayList<>(),
              new ArrayList<>(),
              new ArrayList<>(),
              false,
              null,
              paginator);
      addRowsToMap(rowsByDoc, rows);
    } while (rowsByDoc.keySet().size() <= paginator.docPageSize && paginator.hasDbPageState());

    // Either we've reached the end of all rows in the collection, or we have enough rows
    // in memory to build the final result.
    if (rowsByDoc.keySet().size() > paginator.docPageSize) {
      MutableObject<String> lastIdSeen = new MutableObject<>();
      rowsByDoc.entrySet().stream()
          .limit(paginator.docPageSize)
          .forEach(
              e -> {
                List<Row> rows =
                    e.getValue().stream()
                        .map(
                            row -> {
                              lastIdSeen.setValue(row.getString("key"));
                              return row;
                            })
                        .filter(
                            row ->
                                fields.isEmpty()
                                    || fields.stream()
                                        .anyMatch(
                                            field -> getFieldPathFromRow(row).startsWith(field)))
                        .collect(Collectors.toList());
                docsResult.set(
                    e.getKey(),
                    getJsonConverterService()
                        .convertToJsonDoc(
                            rows, new DeadLeafCollectorNoOp(), false, db.treatBooleansAsNumeric()));
              });

      paginator.setDocumentPageState(lastIdSeen.getValue());
    } else {
      rowsByDoc.entrySet().stream()
          .forEach(
              e -> {
                List<Row> rows =
                    e.getValue().stream()
                        .filter(
                            row ->
                                fields.isEmpty()
                                    || fields.stream()
                                        .anyMatch(field -> fieldMatchesRowPath(row, field)))
                        .collect(Collectors.toList());
                docsResult.set(
                    e.getKey(),
                    getJsonConverterService()
                        .convertToJsonDoc(
                            rows, new DeadLeafCollectorNoOp(), false, db.treatBooleansAsNumeric()));
              });
      paginator.clearDocumentPageState();
    }
    return docsResult;
  }

  /**
   * This method gets all the rows for @param limit documents, by fetching result sets sequentially
   * and stringing them together. After getting all the data out, it will pare down the document to
   * just the relevant result set, while maintaining page state. This is expected to be even more
   * intensive than getFullDocuments.
   */
  public JsonNode getFullDocumentsFiltered(
      DocumentDB db,
      String keyspace,
      String collection,
      List<FilterCondition> filters,
      List<String> fields,
      Paginator paginator)
      throws UnauthorizedException {
    List<FilterCondition> inCassandraFilters =
        filters.stream()
            .filter(f -> !FilterOp.LIMITED_SUPPORT_FILTERS.contains(f.getFilterOp()))
            .collect(Collectors.toList());
    List<FilterCondition> inMemoryFilters =
        filters.stream()
            .filter(f -> FilterOp.LIMITED_SUPPORT_FILTERS.contains(f.getFilterOp()))
            .collect(Collectors.toList());

    if (inCassandraFilters.isEmpty()) {
      return searchWithOnlyInMemoryFilters(
          db, keyspace, collection, inMemoryFilters, fields, paginator);
    }
    return searchUsingCassandraFilters(
        db, keyspace, collection, inCassandraFilters, inMemoryFilters, fields, paginator);
  }

  private JsonNode searchUsingCassandraFilters(
      DocumentDB db,
      String keyspace,
      String collection,
      List<FilterCondition> inCassandraFilters,
      List<FilterCondition> inMemoryFilters,
      List<String> fields,
      Paginator paginator)
      throws UnauthorizedException {
    ObjectMapper mapper = getJsonConverterService().getMapper();
    ObjectNode docsResult = mapper.createObjectNode();
    LinkedHashSet<String> candidates = new LinkedHashSet<>();
    List<Row> rows = null;

    do {
      LinkedHashSet<String> candidateResult =
          getCandidatesForPage(db, keyspace, collection, inCassandraFilters, candidates, paginator);
      candidates = candidateResult;

      // Do an in-memory filter if there are non-cassandra filters
      if (!inMemoryFilters.isEmpty()) {
        BuiltCondition candidatesPredicate =
            BuiltCondition.of("key", Predicate.IN, new ArrayList<>(candidates));
        rows = db.executeSelect(keyspace, collection, ImmutableList.of(candidatesPredicate)).rows();
        candidates.clear();
        updateExistenceForMap(candidates, rows, inMemoryFilters, db.treatBooleansAsNumeric(), true);
      }
    } while (candidates.size() <= paginator.docPageSize && paginator.hasDbPageState());

    // Either we've reached the end of all rows in the collection, or we have enough rows
    // in memory to build the final result.
    if (rows == null) {
      BuiltCondition candidatesPredicate =
          BuiltCondition.of("key", Predicate.IN, new ArrayList<>(candidates));
      rows = db.executeSelect(keyspace, collection, ImmutableList.of(candidatesPredicate)).rows();
      updateExistenceForMap(
          new HashSet<>(), rows, Collections.emptyList(), db.treatBooleansAsNumeric(), true);
    }

    Set<String> docNames = candidates;
    if (candidates.size() > paginator.docPageSize) {
      docNames = new HashSet<>();
      Iterator<String> iter = candidates.iterator();
      String lastSeenId = null;
      for (int i = 0; i < paginator.docPageSize; i++) {
        lastSeenId = iter.next();
        docNames.add(lastSeenId);
      }
      paginator.setDocumentPageState(lastSeenId);
    } else {
      paginator.clearDocumentPageState();
    }

    Map<String, List<Row>> rowsByDoc = new HashMap<>();
    for (Row row : rows) {
      String key = row.getString("key");
      if (docNames.contains(key)) {
        List<Row> rowsAtKey = rowsByDoc.getOrDefault(key, new ArrayList<>());
        if (fields.isEmpty()
            || fields.stream().anyMatch(field -> fieldMatchesRowPath(row, field))) {
          rowsAtKey.add(row);
        }
        rowsByDoc.put(key, rowsAtKey);
      }
    }

    for (Map.Entry<String, List<Row>> entry : rowsByDoc.entrySet()) {
      docsResult.set(
          entry.getKey(),
          getJsonConverterService()
              .convertToJsonDoc(
                  entry.getValue(),
                  new DeadLeafCollectorNoOp(),
                  false,
                  db.treatBooleansAsNumeric()));
    }

    return docsResult;
  }

  private LinkedHashSet<String> getCandidatesForPage(
      DocumentDB db,
      String keyspace,
      String collection,
      List<FilterCondition> inCassandraFilters,
      LinkedHashSet currentCandidateKeys,
      Paginator paginator)
      throws UnauthorizedException {
    LinkedHashSet<String> candidatesThisPage = new LinkedHashSet<>();
    List<Row> page;
    for (int i = 0; i < inCassandraFilters.size(); i++) {
      FilterCondition condition = inCassandraFilters.get(i);
      List<String> path = condition.getPath();

      if (i == 0) {
        page =
            searchRows(
                keyspace,
                collection,
                db,
                Collections.emptyList(),
                ImmutableList.of(condition),
                Collections.emptyList(),
                path,
                false,
                null,
                paginator);
        candidatesThisPage = new LinkedHashSet<>();
        for (Row row : page) {
          candidatesThisPage.add(row.getString("key"));
        }
      } else {
        LinkedHashSet<String> tempCandidates = new LinkedHashSet<>();
        for (String candidate : candidatesThisPage) {
          BuiltCondition candidateCondition = BuiltCondition.of("key", Predicate.EQ, candidate);
          page =
              searchRows(
                  keyspace,
                  collection,
                  db,
                  ImmutableList.of(candidateCondition),
                  ImmutableList.of(condition),
                  Collections.emptyList(),
                  path,
                  false,
                  null,
                  paginator);
          if (!page.isEmpty()) {
            tempCandidates.add(candidate);
          }
        }
        candidatesThisPage = tempCandidates;
      }
    }

    currentCandidateKeys.addAll(candidatesThisPage);
    return currentCandidateKeys;
  }

  private JsonNode searchWithOnlyInMemoryFilters(
      DocumentDB db,
      String keyspace,
      String collection,
      List<FilterCondition> inMemoryFilters,
      List<String> fields,
      Paginator paginator)
      throws UnauthorizedException {
    ObjectMapper mapper = getJsonConverterService().getMapper();
    List<Row> leftoverRows = Collections.emptyList();
    ObjectNode docsResult = mapper.createObjectNode();
    LinkedHashSet<String> existsByDoc = new LinkedHashSet<>();

    do {
      List<Row> page =
          searchRows(
              keyspace,
              collection,
              db,
              Collections.emptyList(),
              new ArrayList<>(),
              Collections.emptyList(),
              Collections.emptyList(),
              false,
              null,
              paginator);
      ArrayList<Row> rowsResult = new ArrayList<>();
      rowsResult.addAll(leftoverRows);
      rowsResult.addAll(page);
      leftoverRows =
          updateExistenceForMap(
              existsByDoc,
              rowsResult,
              inMemoryFilters,
              db.treatBooleansAsNumeric(),
              paginator.hasDbPageState());
    } while (existsByDoc.size() <= paginator.docPageSize && paginator.hasDbPageState());

    // Either we've reached the end of all rows in the collection, or we have enough rows
    // in memory to build the final result.
    Set<String> docNames = existsByDoc;
    if (existsByDoc.size() > paginator.docPageSize) {
      docNames = new HashSet<>();
      Iterator<String> iter = existsByDoc.iterator();
      String lastSeenId = null;
      for (int i = 0; i < paginator.docPageSize; i++) {
        lastSeenId = iter.next();
        docNames.add(lastSeenId);
      }
      paginator.setDocumentPageState(lastSeenId);
    } else {
      paginator.clearDocumentPageState();
    }

    List<BuiltCondition> predicate =
        ImmutableList.of(BuiltCondition.of("key", Predicate.IN, new ArrayList<>(docNames)));

    List<Row> rows = db.executeSelect(keyspace, collection, predicate).rows();
    Map<String, List<Row>> rowsByDoc = new HashMap<>();
    for (Row row : rows) {
      String key = row.getString("key");
      List<Row> rowsAtKey = rowsByDoc.getOrDefault(key, new ArrayList<>());
      if (fields.isEmpty() || fields.stream().anyMatch(field -> fieldMatchesRowPath(row, field))) {
        rowsAtKey.add(row);
      }
      rowsByDoc.put(key, rowsAtKey);
    }

    for (Map.Entry<String, List<Row>> entry : rowsByDoc.entrySet()) {
      docsResult.set(
          entry.getKey(),
          getJsonConverterService()
              .convertToJsonDoc(
                  entry.getValue(),
                  new DeadLeafCollectorNoOp(),
                  false,
                  db.treatBooleansAsNumeric()));
    }

    return docsResult;
  }

  /**
   * Searches a document collection for particular results. If `fields` is non-empty, queries
   * Cassandra for any data that matches `path` and then does matching of the selection set and
   * filtering in memory.
   *
   * <p>A major restriction: if `fields` is non-empty or `filters` includes a filter that has
   * "limited support" ($nin, $in, $ne), then the result set MUST fit in a single page. A requester
   * could alter `page-size` up to a limit of 1000 to attempt to achieve this.
   *
   * @param keyspace the keyspace (document namespace) where the table lives
   * @param collection the table (document collection)
   * @param db the DB utility
   * @param filters A list of FilterConditions
   * @param fields the fields to return
   * @param path the path in the document that is being searched on
   * @param recurse legacy boolean, only used for v1 of the API
   * @param documentKey filter down to only one document's results
   * @param paginator A Paginator object to facilitate paging
   * @return
   * @throws ExecutionException
   * @throws InterruptedException
   */
  @VisibleForTesting
  List<Row> searchRows(
      String keyspace,
      String collection,
      DocumentDB db,
      List<BuiltCondition> additionalWhereConditions,
      List<FilterCondition> filters,
      List<String> fields,
      List<String> path,
      Boolean recurse,
      String documentKey,
      Paginator paginator)
      throws UnauthorizedException {
    StringBuilder pathStr = new StringBuilder();
    List<BuiltCondition> predicates = new ArrayList<>();

    if (!filters.isEmpty() && fields.isEmpty()) {
      FilterCondition first = filters.get(0);
      predicates.add(BuiltCondition.of("leaf", Predicate.EQ, first.getField()));
    }

    boolean manyPathsFound = false;

    int i;
    for (i = 0; i < path.size(); i++) {
      String[] pathSegmentSplit = path.get(i).split(",");
      if (pathSegmentSplit.length == 1) {
        String pathSegment = pathSegmentSplit[0];
        if (pathSegment.equals(DocumentDB.GLOB_VALUE)) {
          manyPathsFound = true;
          predicates.add(BuiltCondition.of("p" + i, Predicate.GT, ""));
        } else {
          String convertedPath = convertArrayPath(pathSegment);
          predicates.add(BuiltCondition.of("p" + i, Predicate.EQ, convertedPath));
          if (!manyPathsFound) {
            pathStr.append("/").append(pathSegment);
          }
        }
      } else {
        List<String> segmentsList = Arrays.asList(pathSegmentSplit);
        // left pad any array segments to 6 places
        segmentsList =
            segmentsList.stream().map(this::convertArrayPath).collect(Collectors.toList());

        manyPathsFound = true;
        predicates.add(BuiltCondition.of("p" + i, Predicate.IN, segmentsList));
      }
    }

    List<FilterCondition> inCassandraFilters = new ArrayList<>();
    List<FilterCondition> inMemoryFilters = filters;

    if (fields.isEmpty()) {
      inCassandraFilters =
          filters.stream()
              .filter(f -> !FilterOp.LIMITED_SUPPORT_FILTERS.contains(f.getFilterOp()))
              .collect(Collectors.toList());
      inMemoryFilters =
          filters.stream()
              .filter(f -> FilterOp.LIMITED_SUPPORT_FILTERS.contains(f.getFilterOp()))
              .collect(Collectors.toList());
    }

    if ((recurse == null || !recurse)
        && path.size() < db.MAX_DEPTH
        && !inCassandraFilters.isEmpty()) {
      predicates.add(BuiltCondition.of("p" + i++, Predicate.EQ, filters.get(0).getField()));
    } else if (!path.isEmpty()) {
      predicates.add(BuiltCondition.of("p" + i++, Predicate.GT, ""));
    }

    // The rest of the paths must match empty-string
    while (i < db.MAX_DEPTH && !path.isEmpty()) {
      predicates.add(BuiltCondition.of("p" + i++, Predicate.EQ, ""));
    }

    for (FilterCondition filter : inCassandraFilters) {
      // All fully supported filters are SingleFilterConditions, as of now
      SingleFilterCondition singleFilter = (SingleFilterCondition) filter;
      FilterOp queryOp = singleFilter.getFilterOp();
      String queryValueField = singleFilter.getValueColumnName();
      Object queryValue = singleFilter.getValue(db.treatBooleansAsNumeric());
      if (queryOp != FilterOp.EXISTS) {
        predicates.add(BuiltCondition.of(queryValueField, queryOp.predicate, queryValue));
      }
    }

    ResultSet r;

    predicates.addAll(additionalWhereConditions);
    if (!predicates.isEmpty()) {
      r =
          db.executeSelect(
              keyspace,
              collection,
              predicates,
              true,
              paginator.dbPageSize,
              paginator.getCurrentDbPageState());
    } else {
      r =
          db.executeSelectAll(
              keyspace, collection, paginator.dbPageSize, paginator.getCurrentDbPageState());
    }

    List<Row> rows = r.currentPageRows();

    paginator.useResultSet(r);

    if (documentKey != null) {
      rows =
          rows.stream()
              .filter(row -> row.getString("key").equals(documentKey))
              .collect(Collectors.toList());
    }

    if (!inMemoryFilters.isEmpty() && paginator.hasDbPageState()) {
      throw new DocumentAPIRequestException(
          "The results as requested must fit in one page, try increasing the `page-size` parameter.");
    }

    rows = filterToSelectionSet(rows, fields, path);
    rows =
        applyInMemoryFilters(
            rows, inMemoryFilters, Math.max(fields.size(), 1), db.treatBooleansAsNumeric());

    return rows;
  }

  private String getParentPathFromRow(Row row) {
    int i = 0;
    StringBuilder s = new StringBuilder();
    boolean end = false;
    s.append(row.getString("key")).append("/");
    while (i < DocumentDB.MAX_DEPTH && !end) {
      String pathSegment = row.getString("p" + i);
      String nextPathSegment = i + 1 < DocumentDB.MAX_DEPTH ? row.getString("p" + (i + 1)) : null;
      end = (nextPathSegment == null || nextPathSegment.equals(""));
      if (!end) {
        s.append(pathSegment).append(".");
        i++;
      }
    }
    return s.toString();
  }

  private String getFieldPathFromRow(Row row) {
    List<String> path = new ArrayList<>();
    for (int i = 0; i < DocumentDB.MAX_DEPTH; i++) {
      String value = row.getString("p" + i);
      if (value.isEmpty()) {
        break;
      }
      path.add(value);
    }
    return Joiner.on(".").join(path);
  }

  private boolean pathsMatch(String path1, String path2) {
    String[] parts1 = PERIOD_PATTERN.split(path1);
    String[] parts2 = PERIOD_PATTERN.split(path2);
    if (parts1.length != parts2.length) {
      return false;
    }

    for (int i = 0; i < parts1.length; i++) {
      String part1 = parts1[i];
      String part2 = parts2[i];
      if (!part1.equals("*") && !part2.equals("*") && !part1.equals(part2)) {
        return false;
      }
    }
    return true;
  }

  private List<Row> filterToSelectionSet(
      List<Row> rows, List<String> fieldNames, List<String> requestedPath) {
    if (fieldNames.isEmpty()) {
      return rows;
    }
    // The expectation here is that if N rows match the inMemoryFilters,
    // there will be exactly N * selectionSet.size() rows in this list, and they will
    // be grouped in order of the rows' path. This means adding in "fluff" empty or null rows
    // to round out the size of the list when a field doesn't exist.
    List<Row> normalizedList = new ArrayList<>();
    Set<String> selectionSet = new HashSet<>(fieldNames);
    String currentPath = "";
    int docSize = 0;
    for (Row row : rows) {
      String path = getParentPathFromRow(row);
      if (!currentPath.equals(path)
          && !currentPath.isEmpty()
          && PERIOD_PATTERN.split(currentPath).length == requestedPath.size()) {
        for (int i = 0; i < selectionSet.size() - docSize; i++) {
          normalizedList.add(null);
        }
        docSize = 0;
      }
      currentPath = path;

      if (selectionSet.contains(row.getString("leaf"))
          && PERIOD_PATTERN.split(currentPath).length == requestedPath.size()) {
        normalizedList.add(row);
        docSize++;
      }
    }

    if (PERIOD_PATTERN.split(currentPath).length == requestedPath.size()) {
      for (int i = 0; i < selectionSet.size() - docSize; i++) {
        normalizedList.add(null);
      }
    }

    return normalizedList;
  }

  /**
   * Applies all of the @param inMemoryFilters, conjunctively (using AND).
   *
   * @param rows the result set from cassandra
   * @param inMemoryFilters the filters to apply to `rows`
   * @param fieldsPerDoc The number of rows that make up a single document result
   * @return rows for each doc that match all filters
   */
  private List<Row> applyInMemoryFilters(
      List<Row> rows,
      List<FilterCondition> inMemoryFilters,
      int fieldsPerDoc,
      boolean numericBooleans) {
    if (inMemoryFilters.isEmpty()) {
      return rows;
    }
    Set<String> filterFieldPaths =
        inMemoryFilters.stream().map(FilterCondition::getFullFieldPath).collect(Collectors.toSet());
    return Lists.partition(rows, fieldsPerDoc).stream()
        .filter(
            docChunk -> {
              List<Row> fieldRows =
                  docChunk.stream()
                      .filter(
                          r -> {
                            if (r == null || r.getString("leaf") == null) {
                              return false;
                            }
                            List<String> parentPath =
                                PATH_SPLITTER.splitToList(getParentPathFromRow(r));
                            String rowPath = "";
                            if (parentPath.size() == 2) {
                              rowPath = parentPath.get(1);
                            }
                            String fullRowPath = rowPath + r.getString("leaf");
                            List<FilterCondition> matchingFilters =
                                inMemoryFilters.stream()
                                    .filter(f -> pathsMatch(fullRowPath, f.getFullFieldPath()))
                                    .collect(Collectors.toList());
                            if (matchingFilters.isEmpty()) {
                              return false;
                            }
                            return allFiltersMatch(r, matchingFilters, numericBooleans);
                          })
                      .collect(Collectors.toList());
              // This ensures that wildcard paths are properly counted with non-wildcard paths,
              // by making sure that for every filter above, at least one matches a valid row.
              return filterFieldPaths.stream()
                  .allMatch(
                      fieldPath ->
                          fieldRows.stream()
                              .anyMatch(
                                  row -> {
                                    List<String> segments =
                                        PATH_SPLITTER.splitToList(getParentPathFromRow(row));
                                    String path = segments.get(segments.size() - 1);
                                    return pathsMatch(path + row.getString("leaf"), fieldPath);
                                  }));
            })
        .flatMap(x -> x.stream())
        .collect(Collectors.toList());
  }

  private Boolean getBooleanFromRow(Row row, String colName, boolean numericBooleans) {
    if (row.isNull("bool_value")) return null;
    if (numericBooleans) {
      byte value = row.getByte(colName);
      return value != 0;
    }
    return row.getBoolean(colName);
  }

  private boolean allFiltersMatch(Row row, List<FilterCondition> filters, boolean numericBooleans) {
    String textValue = row.isNull("text_value") ? null : row.getString("text_value");
    Boolean boolValue = getBooleanFromRow(row, "bool_value", numericBooleans);
    Double dblValue = row.isNull("dbl_value") ? null : row.getDouble("dbl_value");
    for (FilterCondition fc : filters) {
      if (fc.getFilterOp() == FilterOp.EXISTS) {
        if (textValue == null && boolValue == null && dblValue == null) {
          return false;
        }
      } else if (fc.getFilterOp() == FilterOp.EQ) {
        Boolean res = checkEqualsOp((SingleFilterCondition) fc, textValue, boolValue, dblValue);
        if (res == null || !res) {
          return false;
        }
      } else if (fc.getFilterOp() == FilterOp.NE) {
        Boolean res = checkEqualsOp((SingleFilterCondition) fc, textValue, boolValue, dblValue);
        if (res == null || res) {
          return false;
        }
      } else if (fc.getFilterOp() == FilterOp.IN) {
        Boolean res = checkInOp((ListFilterCondition) fc, textValue, boolValue, dblValue);
        if (res == null || !res) {
          return false;
        }
      } else if (fc.getFilterOp() == FilterOp.NIN) {
        Boolean res = checkInOp((ListFilterCondition) fc, textValue, boolValue, dblValue);
        if (res == null || res) {
          return false;
        }
      } else if (fc.getFilterOp() == FilterOp.LTE) {
        Boolean res = checkGtOp((SingleFilterCondition) fc, textValue, boolValue, dblValue);
        if (res == null || res) {
          return false;
        }
      } else if (fc.getFilterOp() == FilterOp.GT) {
        Boolean res = checkGtOp((SingleFilterCondition) fc, textValue, boolValue, dblValue);
        if (res == null || !res) {
          return false;
        }
      } else if (fc.getFilterOp() == FilterOp.LT) {
        Boolean res = checkLtOp((SingleFilterCondition) fc, textValue, boolValue, dblValue);
        if (res == null || !res) {
          return false;
        }
      } else if (fc.getFilterOp() == FilterOp.GTE) {
        Boolean res = checkLtOp((SingleFilterCondition) fc, textValue, boolValue, dblValue);
        if (res == null || res) {
          return false;
        }
      } else {
        throw new IllegalStateException(
            String.format("Invalid Filter Operation: %s", fc.getFilterOp()));
      }
    }
    return true;
  }

  private Boolean checkEqualsOp(
      SingleFilterCondition filterCondition, String textValue, Boolean boolValue, Double dblValue) {
    boolean boolValueEqual = Objects.equals(filterCondition.getBooleanValue(), boolValue);
    boolean dblValueEqual =
        Objects.equals(filterCondition.getDoubleValue(), dblValue)
            || (filterCondition.getDoubleValue() != null
                && dblValue != null
                && Math.abs(filterCondition.getDoubleValue() - dblValue) < .000001);
    boolean textValueEqual = StringUtils.equals(filterCondition.getTextValue(), textValue);
    return boolValueEqual && dblValueEqual && textValueEqual;
  }

  private Boolean checkInOp(
      ListFilterCondition filterCondition, String textValue, Boolean boolValue, Double dblValue) {
    List<Object> value = filterCondition.getValue();
    if (boolValue != null) {
      return value.stream()
          .anyMatch(
              v -> {
                if (!(v instanceof Boolean) || v == null) return false;
                return (boolean) v == boolValue;
              });
    } else if (dblValue != null) {
      return value.stream()
          .anyMatch(
              v -> {
                if (!(v instanceof Double || v instanceof Integer) || v == null) return false;
                Double dbl;
                if (v instanceof Integer) {
                  dbl = Double.valueOf((Integer) v);
                } else {
                  dbl = (Double) v;
                }
                return Math.abs(dbl - dblValue) < .000001;
              });
    } else {
      return value.stream()
          .anyMatch(
              v -> {
                if (v != null && !(v instanceof String)) return false;
                return StringUtils.equals((String) v, textValue);
              });
    }
  }

  private Boolean checkGtOp(
      SingleFilterCondition filterCondition, String textValue, Boolean boolValue, Double dblValue) {
    Object value = filterCondition.getValue();
    if (boolValue != null) {
      // In cassandra, True is greater than False
      if (!(value instanceof Boolean) || value == null) return null;
      return boolValue && !((boolean) value);
    } else if (dblValue != null) {
      if (!(value instanceof Double || value instanceof Integer) || value == null) return null;
      Double dbl;
      if (value instanceof Integer) {
        dbl = Double.valueOf((Integer) value);
      } else {
        dbl = (Double) value;
      }
      return dblValue - dbl > .000001;
    } else {
      if (!(value instanceof String) || value == null || textValue == null) return null;
      return ((String) value).compareTo(textValue) < 0;
    }
  }

  private Boolean checkLtOp(
      SingleFilterCondition filterCondition, String textValue, Boolean boolValue, Double dblValue) {
    Object value = filterCondition.getValue();
    if (boolValue != null) {
      // In cassandra, False is less than True
      if (!(value instanceof Boolean) || value == null) return null;
      return !boolValue && ((boolean) value);
    } else if (dblValue != null) {
      if (!(value instanceof Double || value instanceof Integer) || value == null) return null;
      Double dbl;
      if (value instanceof Integer) {
        dbl = Double.valueOf((Integer) value);
      } else {
        dbl = (Double) value;
      }
      return dbl - dblValue > .000001;
    } else {
      if (!(value instanceof String) || value == null || textValue == null) return null;
      return ((String) value).compareTo(textValue) > 0;
    }
  }
}
