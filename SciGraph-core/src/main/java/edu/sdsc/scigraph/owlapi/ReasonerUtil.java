/**
 * Copyright (C) 2014 The SciGraph authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package edu.sdsc.scigraph.owlapi;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

import javax.inject.Inject;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.AddAxiom;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLEquivalentClassesAxiom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChange;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.RemoveAxiom;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.reasoner.OWLReasonerFactory;

import com.google.common.collect.Iterables;

import edu.sdsc.scigraph.owlapi.loader.OwlLoadConfiguration.ReasonerConfiguration;

public class ReasonerUtil {

  private static final Logger logger = Logger.getLogger(ReasonerUtil.class.getName());
  private final static OWLDataFactory factory = OWLManager.getOWLDataFactory();

  private final OWLOntologyManager manager;
  private final OWLOntology ont;
  private OWLReasoner reasoner;
  private final ReasonerConfiguration config;
  private final OWLReasonerFactory reasonerFactory;

  @Inject
  public ReasonerUtil(ReasonerConfiguration reasonerConfig, OWLOntologyManager manager, OWLOntology ont) 
      throws InstantiationException, IllegalAccessException, ClassNotFoundException {
    reasonerFactory = (OWLReasonerFactory) Class.forName(reasonerConfig.getFactory()).newInstance();
    logger.info("Creating reasoner for " + ont);
    reasoner = reasonerFactory.createReasoner(ont);
    logger.info("Completed creating reasoner for " + ont);
    this.config = reasonerConfig;
    this.manager = manager;
    this.ont = ont;
  }

  OWLReasoner getReasoner() {
    return reasoner;
  }

  Collection<OWLOntologyChange> removeAxioms(AxiomType<?> type) {
    Collection<OWLOntologyChange> removals = new HashSet<>();
    for (OWLOntology importedOnt: ont.getImportsClosure()) {
      Set<? extends OWLAxiom> axioms = importedOnt.getAxioms(type);
      removals.addAll(manager.removeAxioms(importedOnt, axioms));
    }
    return removals;
  }

  /***
   * Remove all axioms that would generate extra unsatisfiable classes for the reasoner
   */
  Collection<OWLOntologyChange> removeUnsatisfiableClasses() {
    Collection<OWLOntologyChange> removals = new HashSet<>();
    removals.addAll(removeAxioms(AxiomType.DISJOINT_CLASSES));
    removals.addAll(removeAxioms(AxiomType.DATA_PROPERTY_DOMAIN));
    removals.addAll(removeAxioms(AxiomType.DATA_PROPERTY_RANGE));
    if (removals.size() > 0) {
      reasoner.flush();
    }
    logger.info("Removed " + removals.size() + " axioms to prevent unsatisfiable classes");
    return removals;
  }

  Collection<OWLClass> getUnsatisfiableClasses() {
    return reasoner.getUnsatisfiableClasses().getEntitiesMinusBottom();
  }

  boolean shouldReason() {
    if (!reasoner.isConsistent()) {
      logger.warning("Not reasoning on " + ont + " because it is inconsistent.");
      return false;
    }
    Collection<OWLClass> unsatisfiableClasses = getUnsatisfiableClasses();
    if (!unsatisfiableClasses.isEmpty()) {
      logger.warning("Not reasoning on " + ont + " because " + unsatisfiableClasses.size() + " classes are unsatisfiable");
      logger.warning("For instance: " + Iterables.getFirst(unsatisfiableClasses, null).getIRI().toString() + " unsatisfiable");
      return false;
    }
    return true;
  }

  AddAxiom getCompleteEquivalence(OWLClass ce) {
    Set<OWLClass> equivalentClasses = reasoner.getEquivalentClasses(ce).getEntities();
    OWLEquivalentClassesAxiom equivalentAxiom = factory.getOWLEquivalentClassesAxiom(equivalentClasses);
    return new AddAxiom(ont, equivalentAxiom);
  }

  //Ticket #42
  List<OWLOntologyChange> getDirectInferredEdges(OWLClass ce) {
    List<OWLOntologyChange> changes = new ArrayList<>();
    //find direct inferred superclasses, D
    Set<OWLClass> directSuperclasses = reasoner.getSuperClasses(ce, true).getFlattened();
    //find indirect superclasses, I
    Set<OWLClass> indirectSuperclasses = reasoner.getSuperClasses(ce, false).getFlattened();
    //find asserted superclasses, A
    Set<OWLClassExpression> assertedSuperclasses = ce.asOWLClass().getSuperClasses(ont);
    //for each d in D, add an edge subClassOf(x d)
    for (OWLClass directSuperclass: directSuperclasses) {
      OWLAxiom axiom = factory.getOWLSubClassOfAxiom(ce, directSuperclass);
      AddAxiom addAxiom = new AddAxiom(ont, axiom);
      changes.add(addAxiom);
    }
    //for each a in A
    for (OWLClassExpression assertedSuperclass: assertedSuperclasses) {
      if (assertedSuperclass.isAnonymous()) {
        continue;
      }
      //if a is in I and not in D, then remove edge subClassOf(x a)
      if (indirectSuperclasses.contains(assertedSuperclass.asOWLClass()) &&
          !directSuperclasses.contains(assertedSuperclass.asOWLClass())) {
        OWLAxiom axiom = factory.getOWLSubClassOfAxiom(ce, assertedSuperclass);
        RemoveAxiom removeAxiom = new RemoveAxiom(ont, axiom);
        changes.add(removeAxiom);
      }
    }
    return changes;
  }

  void flush() {
    reasoner.flush();
  }

  public boolean reason() {
    if (config.isRemoveUnsatisfiableClasses()) {
      removeUnsatisfiableClasses();
    }
    if (!shouldReason()) {
      return false;
    }

    List<OWLOntologyChange> changes = new ArrayList<>();

    for (OWLClass ce: ont.getClassesInSignature(true)) {
      if (config.isAddInferredEquivalences()) {
        changes.add(getCompleteEquivalence(ce));
      }
      if (config.isAddDirectInferredEdges()) {
        changes.addAll(getDirectInferredEdges(ce));
      }
    }
    reasoner.dispose();
    logger.info("Applying reasoned axioms: " + changes.size());
    manager.applyChanges(changes);
    logger.info("Completed applying reasoning changes");
    return true;
  }

}
