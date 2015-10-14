package com.act.lcms.db;

import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.Writer;
import java.util.Arrays;
import java.util.List;

public class PlateCompositionWriter {
  public static final String PLATE_TYPE_96_WELLS = "plate_96_well";
  public static final List<String> PLATE_LABELS_SAMPLE_WELLS = Arrays.asList("msid", "composition", "chemical", "note");

  public void writeSampleWellComposition(Writer outputWriter, Plate plate, List<SampleWell> wells) throws IOException {
    PrintWriter w = new PrintWriter(outputWriter, true);
    w.format(">%s\t%s\n", "name", plate.getName());
    w.format(">%s\t%s\n", "description", plate.getDescription());
    w.format(">%s\t%s\n", "barcode", plate.getBarcode());
    w.format(">%s\t%s\n", "location", plate.getLocation());
    w.format(">%s\t%s\n", "plate_type", plate.getPlateType());
    w.format(">%s\t%d\n", "temperature", plate.getTemperature());
    w.println();

    switch (plate.getPlateType()) {
      case PLATE_TYPE_96_WELLS:
        String[][][] tables = new String[4][8][12];
        for (SampleWell well : wells) {
          int y = well.getPlateRow();
          int x = well.getPlateColumn();
          // TODO: should we use a map for this instead to make the table name -> data correspondence clearer?
          tables[0][y][x] = well.getMsid();
          tables[1][y][x] = well.getComposition();
          tables[2][y][x] = well.getChemical();
          tables[3][y][x] = well.getNote();
        }

        for (int i = 0; i < PLATE_LABELS_SAMPLE_WELLS.size(); i++) {
          write96CellTable(w, PLATE_LABELS_SAMPLE_WELLS.get(i), tables[i]);
        }
        break;
      default:
        // TODO: use a better exception type here.
        throw new RuntimeException(String.format("Found unexpected plate type: %s", plate.getPlateType()));
    }

  }

  protected void write96CellTable(PrintWriter w, String tableName, String[][] values) {
    StringBuilder headerBuilder = new StringBuilder(String.format(">>%s", tableName));
    for (int i = 0; i < 12; i++) {
      // Columns are one-indexed in composition tables.
      headerBuilder.append(String.format("\t%d", i + 1));
    }
    w.println(headerBuilder.toString());

    if (values.length != 8) {
      throw new RuntimeException(String.format(
          "ERROR: found incorrect row dimension for 96 well plate.  Expected %d, but got %d.\n", 8, values.length));
    }

    char rowName = 'A';
    for (int i = 0; i < values.length; i++) {
      if (values[i].length != 12) {
        throw new RuntimeException(String.format(
            "ERROR: found incorrect column dimension for 96 well plate.  Expected %d, but got %d.\n",
            12, values.length));
      }
      w.format("%c\t%s\n", rowName, StringUtils.join(values[i], '\t'));
      rowName++;
    }
    w.println();
  }

  public static void main(String[] args) throws Exception {
    String plateBarcode = args[0];

    try (DB db = new DB().connectToDB("jdbc:postgresql://localhost:10000/lcms?user=mdaly")) {
      Plate p = Plate.getPlateByBarcode(db, plateBarcode);
      List<SampleWell> wells = SampleWell.getSampleWellsByPlateId(db, p.getId());

      PlateCompositionWriter writer = new PlateCompositionWriter();
      writer.writeSampleWellComposition(new OutputStreamWriter(System.out), p, wells);
    }
  }
}
