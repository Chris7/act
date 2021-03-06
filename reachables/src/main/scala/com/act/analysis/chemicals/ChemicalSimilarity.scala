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

package com.act.analysis.chemicals

import chemaxon.descriptors.{SimilarityCalculator, SimilarityCalculatorFactory}
import chemaxon.struc.Molecule
import com.act.analysis.chemicals.molecules.MoleculeConversions
import org.apache.log4j.LogManager

object ChemicalSimilarity {
  private val logger = LogManager.getLogger(getClass)

  private implicit def stringToMolecule(s: String): Molecule = MoleculeConversions.stringToMolecule(s)

  def calculatorSettings: Option[String] = _calculatorSettings
  private def calculatorSettings_=(value: String): Unit = _calculatorSettings = Option(value)
  var _calculatorSettings: Option[String] = None

  /**
    * @param userCalculatorSettings The settings with which to apply the similarity calculation with
    *                             The current default value was chosen as Tanimoto
    *                             gives no favor to the query vs target molecule.
    */
  def init(userCalculatorSettings: String = "TANIMOTO"): Unit = {
    require(calculatorSettings.isEmpty, "Chemical similarity calculator was already initialized.")
    calculatorSettings = userCalculatorSettings
    logger.info(s"Using the following settings for Similarity calculations: $userCalculatorSettings")
  }

  def calculateSimilarity(query: Molecule, target: Molecule): Double = helperCalculateSimilarity(query, target)

  def calculateSimilarity(query: String, target: Molecule): Double = helperCalculateSimilarity(query, target)

  def calculateSimilarity(query: Molecule, target: String): Double = helperCalculateSimilarity(query, target)

  def calculateSimilarity(query: String, target: String): Double = helperCalculateSimilarity(query, target)

  /**
    * For two molecules, use a calculator to determine their closeness.
    *
    * @param query              Molecule to use as the query molecule.
    * @param target             Molecule you are targeting to see how similar it is to the query.
    *
    * @return Similarity value between 0 and 1.
    */
  private def helperCalculateSimilarity(query: Molecule, target: Molecule): Double = {
    val simCalc = getSimCalculator(query)
    simCalc.getSimilarity(MoleculeConversions.toIntArray(target))
  }


  /**
    * For two molecules, use a calculator to determine how far away they are
    *
    * @param query  Molecule to use as the query molecule.
    * @param target Molecule you are targeting to see how similar it is to the query.
    *
    * @return Dissimilarity value between 0 and 1.
    */
  private def helperCalculateDissimilarity(query: Molecule, target: Molecule): Double = {
    val simCalc = getSimCalculator(query)
    simCalc.getDissimilarity(MoleculeConversions.toIntArray(target))
  }

  /**
    * Given settings, retrieves a Similarity calculator for those settings and that query molecule.
    *
    * @param queryMolecule Molecule to query
    *
    * @return A Similarity calculator.
    */
  private def getSimCalculator(queryMolecule: Molecule): SimilarityCalculator[Array[Int]] = {
    require(calculatorSettings.isDefined, "Please run ChemicalSimilarity.init() prior to doing comparisons.  " +
      "If you'd like to use a non-default calculator, you can supply those parameters during initialization.")

    // Main advantage of using the factory is that we can set custom params,
    // comes with the disadvantage of needing to make into an Int array.
    val simCalc = SimilarityCalculatorFactory.create(calculatorSettings.get)
    simCalc.setQueryFingerprint(MoleculeConversions.toIntArray(queryMolecule))

    simCalc
  }

  def calculateDissimilarity(query: Molecule, target: Molecule): Double = helperCalculateDissimilarity(query, target)

  def calculateDissimilarity(query: String, target: Molecule): Double = helperCalculateDissimilarity(query, target)

  def calculateDissimilarity(query: Molecule, target: String): Double = helperCalculateDissimilarity(query, target)

  def calculateDissimilarity(query: String, target: String): Double = helperCalculateDissimilarity(query, target)

}
