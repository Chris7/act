package act.installer.reachablesexplorer;


import com.act.jobs.FileChecker;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;


public class WordCloudGenerator {

  /**
   * This class allow the generation of wordclouds, using R, for any inchi having a Bing reference.
   * It requires an R script, that takes an InChI as argument and writes a word cloud to a file
   */

  private static final String RSCRIPT_LOCATION = "src/main/java/act/installer/reachablesexplorer/RWordCloudGenerator.R";

  private Runtime rt;
  private File rScript;

  private String host;
  private String port;


  public WordCloudGenerator(String host, String port) {
    this.host = host;
    this.port = port;

    rt = Runtime.getRuntime();
    rScript = new File(RSCRIPT_LOCATION);
    try {
      FileChecker.verifyInputFile(rScript);
    } catch (IOException e) {
      System.out.println("Error reading r script");
    }
  }

  public void generateWordCloud(String inchi, File file) throws IOException {
    String cmd = String.format("Rscript %s %s %s %s %s", rScript.getAbsolutePath(), inchi, file.getAbsolutePath(), host, port);

    if (!Files.exists(wordcloud.toPath())) {
      try {
        // TODO: this call a CL to run the R script. Maybe use Rengine instead?
        String cmd = String.format("Rscript %s %s %s %s %s", rScript.getAbsolutePath(), inchi, wordcloud.getAbsolutePath(), host, port);
        rt.exec(cmd);
        FileChecker.verifyInputFile(wordcloud);
      } catch (IOException e) {
        LOGGER.error("Unable to generate wordcloud for %s at location %s", inchi, wordcloud.toPath().toString());
        return null;
      } catch (IOException e) {
      e.printStackTrace();
    }
  }

  // TODO: remove main method when done testing
  public static void main(String[] args) {
    WordCloudGenerator g = new WordCloudGenerator("localhost", "27017");
    try {
      g.generateWordCloud("InChI=1S/C8H9NO2/c1-6(10)9-7-2-4-8(11)5-3-7/h2-5,11H,1H3,(H,9,10)", new File("~/test-1"));
    } catch (IOException e) {System.out.println(String.format("Caught expection %s", e.getMessage()));}
  }

}
