package org.twentyn.proteintodna;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.apache.commons.lang3.tuple.Pair;

import java.util.List;
import java.util.Set;

public class DNADesign {

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public Set<String> getDnaDesigns() {
    return dnaDesigns;
  }

  public void setDnaDesigns(Set<String> dnaDesigns) {
    this.dnaDesigns = dnaDesigns;
  }

  public Set<List<String>> getOrganismAndAccessionNumber() {
    return organismAndAccessionNumber;
  }

  public void setOrganismAndAccessionNumber(Set<List<String>> organismAndAccessionNumber) {
    this.organismAndAccessionNumber = organismAndAccessionNumber;
  }

  @JsonProperty("_id")
  private String id;

  @JsonProperty("designs")
  private Set<String> dnaDesigns;

  @JsonProperty("organism_info")
  private Set<List<String>> organismAndAccessionNumber;

  public DNADesign(Set<String> dnaDesigns, Set<List<String>> organismAndAccessionNumber) {
    this.dnaDesigns = dnaDesigns;
    this.organismAndAccessionNumber = organismAndAccessionNumber;
  }
}
