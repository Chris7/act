/*************************************************************************
*                                                                        *
*  This file is part of the 20n/act project.                             *
*  20n/act enables DNA prediction for synthetic biology/bioengineering.  *
*  Copyright (C) 2017 20n Labs, Inc.                                     *
*                                                                        *
*  Please direct all queries to act@20n.com.                             *
*                                                                        *
*  This program is free software: you can redistribute it and/or modify  *
*  it under the terms of the GNU General Public License as published by  *
*  the Free Software Foundation, either version 3 of the License, or     *
*  (at your option) any later version.                                   *
*                                                                        *
*  This program is distributed in the hope that it will be useful,       *
*  but WITHOUT ANY WARRANTY; without even the implied warranty of        *
*  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the         *
*  GNU General Public License for more details.                          *
*                                                                        *
*  You should have received a copy of the GNU General Public License     *
*  along with this program.  If not, see <http://www.gnu.org/licenses/>. *
*                                                                        *
*************************************************************************/

package com.act.biointerpretation.mechanisminspection;

import act.server.NoSQLAPI;
import act.shared.Chemical;
import act.shared.Reaction;
import chemaxon.calculations.clean.Cleaner;
import chemaxon.formats.MolExporter;
import chemaxon.formats.MolImporter;
import chemaxon.license.LicenseManager;
import chemaxon.license.LicenseProcessingException;
import chemaxon.marvin.io.MolExportException;
import chemaxon.reaction.ReactionException;
import chemaxon.reaction.Reactor;
import chemaxon.struc.Molecule;
import chemaxon.struc.MoleculeGraph;
import com.act.biointerpretation.BiointerpretationProcessor;
import com.act.biointerpretation.Utils.ReactionProjector;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The mechanistic validator is used for evaluating whether a particular set of substrates and products represent a
 * valid enzymatic reaction. It takes as input a DB of cofactor processed reactions and tries to match each reaction
 * against a curated set of ROs. Depending on the quality of the match, it scores the RO-Reaction from a 0-5 score
 * scale. The default score is always -1, in which case, the results of the mechanistic validation run is not written
 * to the write DB. Else, the matched ROs will be packaged and written into the reaction in the write DB.
 */
public class MechanisticValidator extends BiointerpretationProcessor {
  private static final Logger LOGGER = LogManager.getFormatterLogger(MechanisticValidator.class);
  private static final String PROCESSOR_NAME = "Mechanistic Validator";

  private static final String DB_PERFECT_CLASSIFICATION = "perfect";
  private static final int TWO_DIMENSION = 2;
  private ErosCorpus erosCorpus;
  private Map<Ero, Reactor> reactors;
  private BlacklistedInchisCorpus blacklistedInchisCorpus;
  private ReactionProjector projector;
  private int eroHitCounter = 0;
  private int cacheHitCounter = 0;

  private Map<Pair<Map<Long, Integer>, Map<Long, Integer>>, Pair<Long, TreeMap<Integer, List<Ero>>>> cachedEroResults =
      new HashMap<>();

  private enum ROScore {
    PERFECT_SCORE(4),
    MANUALLY_VALIDATED_SCORE(3),
    MANUALLY_NOT_VERIFIED_SCORE(2),
    MANUALLY_INVALIDATED_SCORE(0),
    DEFAULT_MATCH_SCORE(1),
    DEFAULT_UNMATCH_SCORE(-1);

    private int score;

    ROScore(int score) {
      this.score = score;
    }

    public int getScore() {
      return score;
    }
  }

  // See https://docs.chemaxon.com/display/FF/InChi+and+InChiKey+export+options for MolExporter options.
  public static final String MOL_EXPORTER_INCHI_OPTIONS_FOR_INCHI_COMPARISON = new StringBuilder("inchi:").
      append("SNon").append(','). // Exclude stereo information.
      append("AuxNone").append(','). // Don't write the AuxInfo block--it just gets in the way.
      append("Woff").append(','). // Disable warnings.  We'll catch any exceptions this produces, but don't care about warnings.
      append("DoNotAddH"). // Don't add H according to usual valences: all H are explicit
      toString();

  @Override
  public String getName() {
    return PROCESSOR_NAME;
  }

  public MechanisticValidator(NoSQLAPI api) {
    super(api);
  }

  public void init() throws IOException, ReactionException, LicenseProcessingException {
    erosCorpus = new ErosCorpus();
    erosCorpus.loadValidationCorpus();

    blacklistedInchisCorpus = new BlacklistedInchisCorpus();
    blacklistedInchisCorpus.loadCorpus();

    projector = new ReactionProjector(true);

    initReactors();

    markInitialized();
  }

  private void initReactors(File licenseFile) throws IOException, LicenseProcessingException, ReactionException {
    if (licenseFile != null) {
      LicenseManager.setLicenseFile(licenseFile.getAbsolutePath());
    }

    reactors = new HashMap<>(erosCorpus.getRos().size());
    for (Ero ro : erosCorpus.getRos()) {
      try {
        Reactor reactor = new Reactor();
        reactor.setReactionString(ro.getRo());
        reactors.put(ro, reactor);
      } catch (java.lang.NoSuchFieldError e) {
        // TODO: Investigate why so many ROs are failing at this point.
        LOGGER.error("Ros is throwing a no such field error: %s", ro.getRo());
      }
    }
  }

  @Override
  protected void afterProcessReactions() throws IOException, ReactionException {
    super.afterProcessReactions();
    LOGGER.info("Found %d reactions that matched at least one ERO", eroHitCounter);
    LOGGER.info("Observed %d ERO projection cache hits based on substrates/products", cacheHitCounter);
  }

  @Override
  protected Reaction runSpecializedReactionProcessing(Reaction rxn, Long newId) throws IOException {
    return runEROsOnReaction(rxn, newId);
  }

  private Reaction runEROsOnReaction(Reaction rxn, Long newId) throws IOException {
    projector.clearInchiCache(); // Mew reaction probably doesn't have repeat chemicals, and we don't want a huge cache
    // Apply the EROs and save the results in the reaction object.
    TreeMap<Integer, List<Ero>> scoreToListOfRos;
    try {
      /* api.writeToOutKnowledgeGraph doesn't update the id of the written reaction, so we have to pass it as a
       * separate parameter. :(  I would fix the MongoDB behavior, but don't know what that might break!!! */
      scoreToListOfRos = findBestRosThatCorrectlyComputeTheReaction(rxn, newId);
    } catch (IOException e) {
      // Log some information about the culprit when validation fails.
      LOGGER.error("Caught IOException when applying ROs to rxn %d): %s", newId, e.getMessage());
      throw e;
    }

    if (scoreToListOfRos != null && scoreToListOfRos.size() > 0) {
      JSONObject matchingEros = new JSONObject();
      for (Map.Entry<Integer, List<Ero>> entry : scoreToListOfRos.entrySet()) {
        for (Ero e : entry.getValue()) {
          matchingEros.put(e.getId().toString(), entry.getKey().toString());
        }
      }
      rxn.setMechanisticValidatorResult(matchingEros);
      eroHitCounter++;
    }

    return rxn;
  }

  private TreeMap<Integer, List<Ero>> findBestRosThatCorrectlyComputeTheReaction(Reaction rxn, Long newRxnId)
      throws IOException {
    /* Look up any cached results and return immediately if they're available.
     * Note: this only works while EROs ignore cofactors.  If cofactors need to be involved, we should just remove this.
     */
    Map<Long, Integer> substrateToCoefficientMap = new HashMap<>();
    Map<Long, Integer> productToCoefficientMap = new HashMap<>();

    for (Long id : rxn.getSubstrates()) {
      substrateToCoefficientMap.put(id, rxn.getSubstrateCoefficient(id));
    }
    for (Long id : rxn.getProducts()) {
      productToCoefficientMap.put(id, rxn.getSubstrateCoefficient(id));
    }

    {
      Pair<Long, TreeMap<Integer, List<Ero>>> cachedResults =
          cachedEroResults.get(Pair.of(substrateToCoefficientMap, productToCoefficientMap));
      if (cachedResults != null) {
        LOGGER.debug("Got hit on cached ERO results: %d == %d", newRxnId, cachedResults.getLeft());
        cacheHitCounter++;
        return cachedResults.getRight();
      }
    }

    List<Molecule> substrateMolecules = new ArrayList<>();
    for (Long id : rxn.getSubstrates()) {
      String inchi = mapNewChemIdToInChI(id);
      if (inchi == null) {
        String msg = String.format("Missing inchi for new chem id %d in cache", id);
        LOGGER.error(msg);
        throw new RuntimeException(msg);
      }

      if (inchi.contains("FAKE")) {
        LOGGER.debug("The inchi is a FAKE, so just ignore the chemical.");
        continue;
      }

      Molecule mol;
      try {
        mol = MolImporter.importMol(blacklistedInchisCorpus.renameInchiIfFoundInBlacklist(inchi));

        // We had to clean the molecule after importing since based on our testing, the RO only matched the molecule
        // once we cleaned it. Else, the RO did not match the chemical.
        Cleaner.clean(mol, TWO_DIMENSION);

        // We had to aromatize the molecule so that aliphatic related ROs do not match with aromatic compounds.
        mol.aromatize(MoleculeGraph.AROM_BASIC);
      } catch (chemaxon.formats.MolFormatException e) {
        LOGGER.error("Error occurred while trying to import inchi %s: %s", inchi, e.getMessage());
        return null;
      }

      /* Some ROs depend on multiple copies of a given molecule (like #165), and the Reactor won't run without all of
       * those molecules available.  Duplicate a molecule in the substrates list based on its coefficient in the
       * reaction. */
      Integer coefficient = rxn.getSubstrateCoefficient(id);
      if (coefficient == null) {
        // Default to just one if we don't have a clear coefficient to use.
        LOGGER.warn("Converting coefficient null -> 1 for rxn %d/chem %d", newRxnId, id);
        coefficient = 1;
      }

      for (int i = 0; i < coefficient; i++) {
        substrateMolecules.add(mol);
      }
    }

    Set<String> expectedProducts = new HashSet<>();

    for (Long id : rxn.getProducts()) {
      String inchi = mapNewChemIdToInChI(id);
      if (inchi == null) {
        String msg = String.format("Missing inchi for new chem id %d in cache", id);
        LOGGER.error(msg);
        throw new RuntimeException(msg);
      }

      if (inchi.contains("FAKE")) {
        LOGGER.debug("The inchi is a FAKE, so just ignore the chemical.");
        continue;
      }

      String transformedInchi = removeChiralityFromChemical(inchi);
      if (transformedInchi == null) {
        return null;
      }
      expectedProducts.add(transformedInchi);
    }

    TreeMap<Integer, List<Ero>> scoreToListOfRos = new TreeMap<>(Collections.reverseOrder());
    for (Map.Entry<Ero, Reactor> entry : reactors.entrySet()) {
      Integer score =
          scoreReactionBasedOnRO(entry.getValue(), substrateMolecules, expectedProducts, entry.getKey(), newRxnId);
      if (score > ROScore.DEFAULT_UNMATCH_SCORE.getScore()) {
        List<Ero> vals = scoreToListOfRos.get(score);
        if (vals == null) {
          vals = new ArrayList<>();
          scoreToListOfRos.put(score, vals);
        }
        vals.add(entry.getKey());
      }
    }

    // Cache results for any future similar reactions.
    cachedEroResults.put(Pair.of(substrateToCoefficientMap, productToCoefficientMap),
        Pair.of(newRxnId, scoreToListOfRos));

    return scoreToListOfRos;
  }

  private String removeChiralityFromChemical(String inchi) throws IOException {
    try {
      Molecule importedMol = MolImporter.importMol(blacklistedInchisCorpus.renameInchiIfFoundInBlacklist(inchi));
      Cleaner.clean(importedMol, TWO_DIMENSION);
      importedMol.aromatize(MoleculeGraph.AROM_BASIC);
      return MolExporter.exportToFormat(importedMol, MOL_EXPORTER_INCHI_OPTIONS_FOR_INCHI_COMPARISON);
    } catch (chemaxon.formats.MolFormatException e) {
      LOGGER.error("Error occured while trying to import/export molecule from inchi %s: %s", inchi, e.getMessage());
      return null;
    } catch (MolExportException e) {
      LOGGER.error("Error occured while trying to import/export molecule from inchi %s: %s", inchi, e.getMessage());
      return null;
    }
  }

  public void initReactors() throws IOException, LicenseProcessingException, ReactionException {
    initReactors(null);
  }

  /**
   * Tests if an ero matches a reaction in the DB, and returns the appropriate validation score based on whether it
   * matches and the properties of the ero.
   *
   * @param reactor               The reactor for the Ro.
   * @param substrates            The substrates of the reaction.
   * @param expectedProductInchis The expected inchis from the DB.
   * @param ero                   The RO.
   * @param newRxnId              The new reaction ID if a match is found.
   * @return
   */
  public Integer scoreReactionBasedOnRO(
      Reactor reactor, List<Molecule> substrates, Set<String> expectedProductInchis, Ero ero, Long newRxnId) {

    Molecule[] substrateArray = substrates.toArray(new Molecule[substrates.size()]);
    List<Molecule[]> productSets;

    try {
      productSets = projector.getAllProjectedProductSets(substrateArray, reactor, 10);
    } catch (IOException e) {
      LOGGER.error("Encountered IOException when projecting reactor for ERO %d onto substrates of %d: %s",
          ero.getId(), newRxnId, e.getMessage());
      return ROScore.DEFAULT_UNMATCH_SCORE.getScore();
    } catch (ReactionException e) {
      LOGGER.error("Encountered ReactionException when projecting reactor for ERO %d onto substrates of %d: %s",
          ero.getId(), newRxnId, e.getMessage());
      return ROScore.DEFAULT_UNMATCH_SCORE.getScore();
    }

    if (productSets.isEmpty()) {
      LOGGER.debug("No products were generated from the projection");
      return ROScore.DEFAULT_UNMATCH_SCORE.getScore();
    }

    for (Molecule[] products : productSets) {
      // If one of the product sets completely matches the expected product inchis set, we are confident that
      // the reaction can be explained by the RO.
      for (String productInchi : getInchiSet(products)) {
        if (expectedProductInchis.contains(productInchi)) {
          return getMatchScore(ero);
        }
      }
    }
    return ROScore.DEFAULT_UNMATCH_SCORE.getScore();
  }

  /**
   * Returns the validation score that an ero will produce if it matches a reaction in the DB.
   *
   * @param ero The ero.
   * @return The score.
   */
  private Integer getMatchScore(Ero ero) {
    if (ero.getCategory().equals(DB_PERFECT_CLASSIFICATION)) {
      return ROScore.PERFECT_SCORE.getScore();
    }

    if (ero.getManual_validation()) {
      return ROScore.MANUALLY_VALIDATED_SCORE.getScore();
    }

    if (ero.getManual_validation() == null) {
      return ROScore.MANUALLY_NOT_VERIFIED_SCORE.getScore();
    }

    if (!ero.getManual_validation()) {
      return ROScore.MANUALLY_INVALIDATED_SCORE.getScore();
    }

    return ROScore.DEFAULT_MATCH_SCORE.getScore();
  }

  /**
   * Turns an array of molecules, i.e. a product array produced by a Reactor, into a Set of inchis
   *
   * @param molsArray The array of molecules.
   * @return The corresponding set of inchis.
   */
  private Set<String> getInchiSet(Molecule[] molsArray) {
    Set<String> result = new HashSet<>();

    for (Molecule product : molsArray) {
      String inchi;
      try {
        inchi = MolExporter.exportToFormat(product, MOL_EXPORTER_INCHI_OPTIONS_FOR_INCHI_COMPARISON);
      } catch (IOException e) {
        LOGGER.error("Unable to export molecule to InChI, skipping: %s", e.getMessage());
        continue;
      }
      result.add(inchi);
    }

    return result;
  }

  /**
   * Validate a single reaction by its ID.
   *
   * Important: do not call this on an object that has been/will be used to validate an entire DB (via the `run` method,
   * for example).  The two approaches to validation use the same cache objects which will be corrupted if the object
   * is reused (hence this method being protected).
   *
   * @param rxnId The id of the reaction to validate.
   * @return Scored ERO projection results or null if an error occurred.
   * @throws IOException
   */
  public Map<Integer, List<Ero>> validateOneReaction(Long rxnId) throws IOException {
    Reaction rxn = getNoSQLAPI().readReactionFromInKnowledgeGraph(rxnId);
    if (rxn == null) {
      LOGGER.error("Could not find reaction %d in the DB", rxnId);
      return null;
    }

    Set<Long> allChemicalIds = new HashSet<>();
    allChemicalIds.addAll(Arrays.asList(rxn.getSubstrates()));
    allChemicalIds.addAll(Arrays.asList(rxn.getProducts()));
    allChemicalIds.addAll(Arrays.asList(rxn.getSubstrateCofactors()));
    allChemicalIds.addAll(Arrays.asList(rxn.getProductCofactors()));

    for (Long id : allChemicalIds) {
      Chemical chem = getNoSQLAPI().readChemicalFromInKnowledgeGraph(id);
      if (chem == null) {
        LOGGER.error("Unable to find chemical %d for reaction %d in the DB", id, rxnId);
        return null;
      }
      // Simulate chemical migration so we play nicely with the validator.
      getOldChemIdToNewChemId().put(id, id);
      getNewChemIdToInchi().put(id, chem.getInChI());
    }

    return findBestRosThatCorrectlyComputeTheReaction(rxn, rxnId);
  }
}
