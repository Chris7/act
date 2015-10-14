package com.act.lcms.db;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class CuratedChemical {
  public static final String TABLE_NAME = "chemicals_curated";

  public static String getTableName() {
    return TABLE_NAME;
  }

  // TODO: it might be easier to use parts of Spring-Standalone to do named binding in these queries.
  protected static final List<String> ALL_FIELDS = Collections.unmodifiableList(Arrays.asList(
      "id", // 1
      "name", // 2
      "inchi", // 3
      "m_plus_h_plus_mass", // 4
      "expected_collision_voltage", // 5
      "reference_url" // 6
  ));
  // id is auto-generated on insertion.
  protected static final List<String> INSERT_UPDATE_FIELDS =
      Collections.unmodifiableList(ALL_FIELDS.subList(1, ALL_FIELDS.size()));

  protected static List<CuratedChemical> fromResultSet(ResultSet resultSet) throws SQLException {
    List<CuratedChemical> results = new ArrayList<>();
    while (resultSet.next()) {
      Integer id = resultSet.getInt(1);
      String name = resultSet.getString(2);
      String inchi = resultSet.getString(3);
      Double mPlusHPlusMass = resultSet.getDouble(4);
      Integer expectedCollisionVoltage = resultSet.getInt(5);
      if (resultSet.wasNull()) {
        expectedCollisionVoltage = null;
      }
      String referenceUrl = resultSet.getString(6);

      results.add(new CuratedChemical(id, name, inchi, mPlusHPlusMass, expectedCollisionVoltage, referenceUrl));
    }

    return results;
  }

  protected static CuratedChemical expectOneResult(ResultSet resultSet, String queryErrStr) throws SQLException{
    List<CuratedChemical> results = fromResultSet(resultSet);
    if (results.size() > 1) {
      throw new SQLException("Found multiple results where one or zero expected: %s", queryErrStr);
    }
    if (results.size() == 0) {
      return null;
    }
    return results.get(0);
  }

  // Select
  public static final String QUERY_GET_CURATED_CHEMICAL_BY_ID = StringUtils.join(new String[]{
      "SELECT", StringUtils.join(ALL_FIELDS, ", "),
      "from", TABLE_NAME,
      "where id = ?",
  }, " ");
  public static CuratedChemical getCuratedChemicalById(DB db, Integer id) throws SQLException {
    try (PreparedStatement stmt = db.getConn().prepareStatement(QUERY_GET_CURATED_CHEMICAL_BY_ID)) {
      stmt.setInt(1, id);
      try (ResultSet resultSet = stmt.executeQuery()) {
        return expectOneResult(resultSet, String.format("id = %d", id));
      }
    }
  }

  public static final String QUERY_GET_CURATED_CHEMICAL_BY_NAME = StringUtils.join(new String[]{
      "SELECT", StringUtils.join(ALL_FIELDS, ", "),
      "from", TABLE_NAME,
      "where name = ?",
  }, " ");
  public static CuratedChemical getCuratedChemicalByName(DB db, String name) throws SQLException {
    try (PreparedStatement stmt = db.getConn().prepareStatement(QUERY_GET_CURATED_CHEMICAL_BY_NAME)) {
      stmt.setString(1, name);
      try (ResultSet resultSet = stmt.executeQuery()) {
        return expectOneResult(resultSet, String.format("name = %s", name));
      }
    }
  }

  public static final String QUERY_GET_CURATED_CHEMICAL_BY_INCHI = StringUtils.join(new String[]{
      "SELECT", StringUtils.join(ALL_FIELDS, ", "),
      "from", TABLE_NAME,
      "where inchi = ?",
  }, " ");
  public static CuratedChemical getCuratedChemicalByInChI(DB db, String inchi) throws SQLException {
    try (PreparedStatement stmt = db.getConn().prepareStatement(QUERY_GET_CURATED_CHEMICAL_BY_INCHI)) {
      stmt.setString(1, inchi);
      try (ResultSet resultSet = stmt.executeQuery()) {
        return expectOneResult(resultSet, String.format("inchi = %s", inchi));
      }
    }
  }

  // Insert/Update
  public static final String QUERY_INSERT_CURATED_CHEMICAL = StringUtils.join(new String[] {
      "INSERT INTO", TABLE_NAME, "(", StringUtils.join(INSERT_UPDATE_FIELDS, ", "), ") VALUES (",
      "?,", // 1 = name
      "?,", // 2 = inchi
      "?,", // 3 = m_plus_h_plus_mass
      "?,", // 4 = expected_collision_voltage
      "?", // 5 = reference_url
      ")"
  }, " ");

  protected static void bindInsertOrUpdateParameters(
      PreparedStatement stmt, String name, String inchi, Double mPlusHPlusMass,
      Integer expectedCollisionVoltage, String referenceUrl) throws SQLException {
    stmt.setString(1, name);
    stmt.setString(2, inchi);
    stmt.setDouble(3, mPlusHPlusMass);
    if (expectedCollisionVoltage != null) {
      stmt.setInt(4, expectedCollisionVoltage);
    } else {
      stmt.setNull(4, Types.INTEGER);
    }
    stmt.setString(5, referenceUrl);
  }

  // TODO: this could return the number of parameters it bound to make it easier to set additional params.
  protected static void bindInsertOrUpdateParameters(PreparedStatement stmt, CuratedChemical c) throws SQLException {
    bindInsertOrUpdateParameters(stmt, c.getName(), c.getInchi(), c.getmPlusHPlusMass(),
        c.getExpectedCollisionVoltage(), c.getReferenceUrl());
  }

  public static CuratedChemical insertCuratedChemical(
      DB db,
      String name, String inchi, Double mPlusHPlusMass,
      Integer expectedCollisionVoltage, String referenceUrl) throws SQLException {
    Connection conn = db.getConn();
    try (PreparedStatement stmt =
             conn.prepareStatement(QUERY_INSERT_CURATED_CHEMICAL, Statement.RETURN_GENERATED_KEYS)) {
      bindInsertOrUpdateParameters(stmt, name, inchi, mPlusHPlusMass, expectedCollisionVoltage, referenceUrl);
      stmt.executeUpdate();
      try (ResultSet resultSet = stmt.getGeneratedKeys()) {
        if (resultSet.next()) {
          // Get auto-generated id.
          int id = resultSet.getInt(1);
          return new CuratedChemical(id, name, inchi, mPlusHPlusMass, expectedCollisionVoltage, referenceUrl);
        } else {
          System.err.format("ERROR: could not retrieve autogenerated key for curated chemical %s\n", name);
          return null;
        }
      }
    }
  }

  protected static final List<String> UPDATE_STATEMENT_FIELDS_AND_BINDINGS;

  static {
    List<String> fields = new ArrayList<>(INSERT_UPDATE_FIELDS.size());
    for (String field : INSERT_UPDATE_FIELDS) {
      fields.add(String.format("%s = ?", field));
    }
    UPDATE_STATEMENT_FIELDS_AND_BINDINGS = Collections.unmodifiableList(fields);
  }
  public static final String QUERY_UPDATE_CURATED_CHEMICAL_BY_ID = StringUtils.join(new String[] {
      "UPDATE ", TABLE_NAME, "SET",
      StringUtils.join(UPDATE_STATEMENT_FIELDS_AND_BINDINGS.iterator(), ", "),
      "WHERE",
      "id = ?", // 6
  }, " ");
  public static boolean updateCuratedChemical(DB db, CuratedChemical chem) throws SQLException {
    Connection conn = db.getConn();
    try (PreparedStatement stmt = conn.prepareStatement(QUERY_UPDATE_CURATED_CHEMICAL_BY_ID)) {
      bindInsertOrUpdateParameters(stmt, chem);
      stmt.setInt(6, chem.getId());
      return stmt.executeUpdate() > 0;
    }
  }

  public static List<Pair<Integer, DB.OPERATION_PERFORMED>> insertOrUpdateCuratedChemicalsFromTSV(
      DB db, TSVParser parser) throws SQLException{
    List<Map<String, String>> entries = parser.getResults();
    List<Pair<Integer, DB.OPERATION_PERFORMED>> operationsPerformed = new ArrayList<>(entries.size());
    for (Map<String, String> entry : entries) {
      String name = entry.get("name");
      String inchi = entry.get("inchi");
      String mPlusHPlusMassStr = entry.get("[M+H]+");
      String expectedCollisionVoltageStr = entry.get("collision_voltage");
      String referenceUrl = entry.get("reference_url");
      if (name == null || name.isEmpty() ||
          inchi == null || inchi.isEmpty() ||
          mPlusHPlusMassStr == null || mPlusHPlusMassStr.isEmpty()) {
        System.err.format("WARNING: missing required field for chemical '%s', skipping.\n", name);
        continue;
      }

      Double mPlusHPlusMass = Double.parseDouble(mPlusHPlusMassStr);
      Integer expectedCollisionVoltage = expectedCollisionVoltageStr == null || expectedCollisionVoltageStr.isEmpty() ?
          null : Integer.parseInt(expectedCollisionVoltageStr);

      // TODO: should we fall back to searching by name if we can't find the InChI?
      CuratedChemical chem = getCuratedChemicalByInChI(db, inchi);
      DB.OPERATION_PERFORMED op = null;
      if (chem == null) {
        chem = insertCuratedChemical(db, name, inchi, mPlusHPlusMass, expectedCollisionVoltage, referenceUrl);
        op = DB.OPERATION_PERFORMED.CREATE;
      } else {
        chem.setName(name);
        chem.setInchi(inchi);
        chem.setMPlusHPlusMass(mPlusHPlusMass);
        chem.setExpectedCollisionVoltage(expectedCollisionVoltage);
        chem.setReferenceUrl(referenceUrl);
        updateCuratedChemical(db, chem);
        op = DB.OPERATION_PERFORMED.UPDATE;
      }

      // Chem should only be null if we couldn't insert the row into the DB.
      if (chem == null) {
        operationsPerformed.add(Pair.of((Integer)null, DB.OPERATION_PERFORMED.ERROR));
      } else {
        operationsPerformed.add(Pair.of(chem.getId(), op));
      }
    }
    return operationsPerformed;
  }

  private Integer id;
  private String name;
  private String inchi;
  private Double mPlusHPlusMass;
  private Integer expectedCollisionVoltage;
  private String referenceUrl;

  public CuratedChemical(Integer id, String name, String inchi, Double mPlusHPlus,
                         Integer expectedCollisionVoltage, String referenceUrl) {
    this.id = id;
    this.name = name;
    this.inchi = inchi;
    this.mPlusHPlusMass = mPlusHPlus;
    this.expectedCollisionVoltage = expectedCollisionVoltage;
    this.referenceUrl = referenceUrl;
  }

  public Integer getId() {
    return id;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getInchi() {
    return inchi;
  }

  public void setInchi(String inchi) {
    this.inchi = inchi;
  }

  public Double getmPlusHPlusMass() {
    return mPlusHPlusMass;
  }

  public void setMPlusHPlusMass(Double mPlusHPlusMass) {
    this.mPlusHPlusMass = mPlusHPlusMass;
  }

  public Integer getExpectedCollisionVoltage() {
    return expectedCollisionVoltage;
  }

  public void setExpectedCollisionVoltage(Integer expectedCollisionVoltage) {
    this.expectedCollisionVoltage = expectedCollisionVoltage;
  }

  public String getReferenceUrl() {
    return referenceUrl;
  }

  public void setReferenceUrl(String referenceUrl) {
    this.referenceUrl = referenceUrl;
  }

  public static void main(String[] args) throws Exception {
    try (DB db = new DB().connectToDB("jdbc:postgresql://localhost:10000/lcms?user=mdaly")) {
      TSVParser parser = new TSVParser();
      parser.parse(new File(args[0]));
      List<Pair<Integer, DB.OPERATION_PERFORMED>> results = insertOrUpdateCuratedChemicalsFromTSV(db, parser);
      for (Pair<Integer, DB.OPERATION_PERFORMED> r : results) {
        System.out.format("%d: %s\n", r.getLeft(), r.getRight());
      }
    }
  }
}
