package cz.muni.fi.mias.math;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;


import org.apache.commons.io.input.ReaderInputStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.PayloadAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.util.BytesRef;
import org.jdom2.output.DOMOutputter;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.w3c.dom.Text;
import cz.muni.fi.mir.mathmlcanonicalization.MathMLCanonicalizer;
import org.apache.lucene.util.ByteBlockPool;
import org.apache.lucene.util.BytesRefHash;

/**
 * Implementation of Lucene Tokenizer. Provides math formulae contained in the input as
 * string tokens and their weight. These attributes are held in
 * TermAttribute and PayloadAttribute and carried over the stream.
 *
 * @author Martin Liska
 * @since 14.5.2010
 */
public class MathTokenizer extends Tokenizer {

    private static final Logger LOG = Logger.getLogger(MathTokenizer.class.getName());

    /**
     * Maximal filed length according to {@link BytesRefHash}
     */
    public static final int MAX_LUCENE_TEXT_FIELD_LENGTH = ByteBlockPool.BYTE_BLOCK_SIZE - 2;
    
    private static FormulaValuator valuator      = new CountNodesFormulaValuator();
    private static Map<String, List<String>> ops = MathMLConf.getOperators();
    private static Map<String, String> eldict    = MathMLConf.getElementDictionary();
    private static Map<String, String> attrdict  = MathMLConf.getAttrDictionary();;

    // statistics
    private static AtomicLong inputF    = new AtomicLong(0);
    private static AtomicLong producedF = new AtomicLong(0);

    // utilities
    private final MathMLCanonicalizer canonicalizer = MathMLCanonicalizer.getDefaultCanonicalizer();
    private final DOMOutputter outputter = new DOMOutputter();
    
    // configuration
    private float lCoef = 0.7f;
    private float vCoef = 0.8f;
    private float cCoef = 0.5f;
    private final float aCoef = 1.2f;
    private final boolean subformulae;
    private final MathMLType mmlType;
    private int formulaPosition = 1;
    
    // fields with state related to tokenization of current input;
    // fields must be correctly reset in order for this tokenizer to be re-usable
    // (see javadoc of org.apache.lucene.analysis.TokenStream.reset() method)
    private final CharTermAttribute termAtt         = addAttribute(CharTermAttribute.class);
    private final PayloadAttribute payAtt           = addAttribute(PayloadAttribute.class);
    private final PositionIncrementAttribute posAtt = addAttribute(PositionIncrementAttribute.class);
    private final Map<Integer, List<Formula>> formulae = new LinkedHashMap<Integer, List<Formula>>();
    private Iterator<List<Formula>> itMap = Collections.<List<Formula>> emptyList().iterator();
    private Iterator<Formula> itForms = Collections.<Formula> emptyList().iterator();
    private int increment;
    
    public enum MathMLType {
        CONTENT, PRESENTATION, BOTH
    }
    
    /**
     * @param input Reader containing the input to process
     * @param subformulae if true, subformulae will be extracted
     * @param type type of MathML that should be processed
     */
    public MathTokenizer(Reader input, boolean subformulae, MathMLType type) {
        super(input);

        this.mmlType = type;

        this.subformulae = subformulae;
        if (!subformulae) {
            lCoef = 1;
            vCoef = 1;
            cCoef = 1;
        }
    }
    
    /**
     * Overrides the position attribute for all processed formulae
     * 
     * @param formulaPosition Position number to be used for all processed formulae
     */
    public void setFormulaPosition(int formulaPosition) {
        this.formulaPosition = formulaPosition;
        increment = formulaPosition;
    }
    
    @Override
    // NB: TokenStream implementation classes or at least their incrementToken() implementation must be final
    public final boolean incrementToken() {
        clearAttributes();
        if (nextIt()) {
            Formula f = itForms.next();
            termAtt.setEmpty();
            String nodeString = nodeToString(f.getNode(), false);
            if (nodeString.getBytes().length <= MAX_LUCENE_TEXT_FIELD_LENGTH) {
                termAtt.append(nodeString);
            } else {
                // Discard this entire node contents to prevent Lucene indexing failures
                LOG.warning("Node string representation too long (" + nodeString.getBytes().length + " bytes), node contents discarded.");
                termAtt.append("");
            }
            byte[] payload = PayloadHelper.encodeFloatToShort(f.getWeight());
            payAtt.setPayload(new BytesRef(payload));
            posAtt.setPositionIncrement(increment);
            increment = 0;
            return true;
        }
        return false;
    }

    /**
     * Shifts iterator in the formulae map, helping incrementToken() to decide whether or not
     * is there another token available.
     * @return true if there is another formulae in the map,
     *         false otherwise
     */
    private boolean nextIt() {
        while (! itForms.hasNext() && itMap.hasNext()) {
            itForms = itMap.next().iterator();
            increment++;
        }
        
        return itForms.hasNext();
    }

    @Override
    public void reset() throws IOException {
        super.reset();

        processFormulae(input);
    }

    @Override
    public void end() throws IOException {
        super.end();
        
        clearFormulae();
    }
    
    @Override
    public void close() throws IOException {
        super.close();
        
        clearFormulae();
    }

    private void clearFormulae() {
        formulae.clear();
        itMap = Collections.<List<Formula>> emptyList().iterator();;
        itForms = Collections.<Formula> emptyList().iterator();;
    }
    
    /**
     * Performs all the parsing, sorting, modifying and ranking of the formulae contained in the given
     * InputStream.
     * Internal representation of the formula is w3c.dom.Node.
     *
     * @param is InputStream with the formuale.
     * @return Collection of the formulae in the form of Map<Double, List<String>>.
     *         this map gives pairs {created formula, it's rank}. Key of the map is the
     *         rank of the all formulae located in the list specified by the value of the Map.Entry.
     */
    private void processFormulae(Reader input) {
        try {
            clearFormulae();

            Document doc = parseMathML(input);
            if (doc != null) {
                load(doc);
                order();
                modify();
//            printMap(formulae);
                if (subformulae) {
                    for (List<Formula> forms : formulae.values()) {
                        producedF.addAndGet(forms.size());
                    }
                }
            }

            itMap = formulae.values().iterator();
            itForms = Collections.<Formula> emptyList().iterator();;

            increment = formulaPosition - 1; // NB: itForms is set to empty iterator and so increment will get incremented by one in nextIt()
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Could not process formulae.", e);
        }
    }

    private Document parseMathML(Reader input) {
        Document doc;
        
        try {
            org.jdom2.Document jdom2Doc = canonicalizer.canonicalize(new ReaderInputStream(input, "UTF-8"));            
            doc = outputter.output(jdom2Doc);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Input could not be parsed (probably it is not valid MathML)", e);
            doc = null;
        }
        
        return doc;
    }

    /**
     * Loads all the formulae located in given w3c.dom.Document.
     *
     * @param doc DOM Document with formulae
     */
    private void load(Document doc) {
        String mathMLNamespace = MathMLConf.MATHML_NAMESPACE_URI;
        if (!subformulae) {
            mathMLNamespace = "*";
        }
        NodeList list = doc.getElementsByTagNameNS(mathMLNamespace, MathMLConstants.MML_MATH);
        inputF.addAndGet(list.getLength());
        for (int i = 0; i < list.getLength(); i++) {
            Node node = list.item(i);
            formulae.put(i, new ArrayList<Formula>());
            if (subformulae) {
                loadNode(node, 1 / valuator.count(node, mmlType), i);
            } else {
                loadNode(node, valuator.count(node, mmlType), i);
            }
        }
    }

    /**
     * Recursively called when loading also subformulae. Adds all the relevant nodes to the
     * formuale1 map.
     *
     * @param n Node current MathML node.
     * @param level current depth in the original formula tree which is also rank of the this Node
     */
    private void loadNode(Node n, float level, int position) {
        if (n instanceof Element) {
            String name = n.getLocalName();
            if (!MathMLConf.ignoreNodeAndChildren(name)) {
                boolean store = false;
                if ((mmlType==MathMLType.BOTH && MathMLConf.isIndexableElement(name)) ||
                    (mmlType==MathMLType.PRESENTATION && MathMLConf.isPresentationElement(name)) || 
                    (mmlType==MathMLType.CONTENT && MathMLConf.isContentElement(name))) {
                    store = true;
                }
                removeTextNodes(n);
                NodeList nl = n.getChildNodes();
                int length = nl.getLength();
                if (subformulae || !store) {
                    for (int j = 0; j < length; j++) {
                        Node node = nl.item(j);
                        loadNode(node, store? level * lCoef : level, position);
                    }
                }
                if (store && !MathMLConf.ignoreNode(name)) {
                    formulae.get(position).add(new Formula(n, level));
                }            
            }
        }
    }
    
    /**
     * Removes unnecessary text nodes from the markup
     * 
     * @param node 
     */
    private void removeTextNodes(Node node) {
        NodeList nl = node.getChildNodes();
        int length = nl.getLength();
        int removed = 0;
        for (int j = 0; j < length; j++) {
            Node n = nl.item(removed);
            if ((n instanceof Text) && (n.getTextContent().trim().length() == 0)) {
                node.removeChild(n);
            } else {
                removed++;
                removeTextNodes(n);
            }
        }
    }

    /**
     * Removes all attributes except those specified in the attr-dict configuration file
     * 
     * @param rank factor by which the formulae keeping the attributes increase their weight
     */
    private void processAttributes(float rank) {
        List<Formula> result = new ArrayList<Formula>();
        for (List<Formula> forms : formulae.values()) {
            result.clear();
            for (Formula f : forms) {
                Node node = f.getNode();
                Node newNode = node.cloneNode(true);
                removeAttributes(node);
                boolean changed = processAttributesNode(newNode);
                if (changed) {
                    result.add(new Formula(newNode, f.getWeight() * rank));
                }
            }
            forms.addAll(result);
        }
    }

    private boolean processAttributesNode(Node node) {
        boolean result = false;
        NodeList nl = node.getChildNodes();
        int length = nl.getLength();
        for (int j = 0; j < length; j++) {
            Node n = nl.item(j);
            result = processAttributesNode(n) == false ? result : true;
        }
        if (node.hasAttributes()) {
            NamedNodeMap attrs = node.getAttributes();
            Set<Node> keepAttrs = new HashSet<Node>();
            for (String dictAttr : attrdict.keySet()) {
                Node keepAttr = attrs.getNamedItem(dictAttr);
                if (keepAttr != null) {
                    keepAttrs.add(keepAttr);
                }
            }
            removeAttributes(node);
            for (Node n : keepAttrs) {
                attrs.setNamedItem(n);
                result = true;
            }
        }
        return result;
    }

    private void removeAttributes(Node node) {
        removeAttributesNode(node);
        NodeList nl = node.getChildNodes();
        int length = nl.getLength();
        for (int j = 0; j < length; j++) {
            removeAttributes(nl.item(j));
        }
    }

    private void removeAttributesNode(Node node) {
        if (node.hasAttributes()) {
            NamedNodeMap attrs = node.getAttributes();
            String[] names = new String[attrs.getLength()];
            for (int i = 0; i < names.length; i++) {
                names[i] = attrs.item(i).getNodeName();
            }
            for (int i = 0; i < names.length; i++) {
                attrs.removeNamedItem(names[i]);
            }
        }
    }

    /**
     * Provides sorting of elements in MathML formula based on the NodeName. Sorting is
     * done for operators from the operators configuration file. All sorted formulae
     * replace their original forms in the formulae map.
     */
    private void order() {
        for (List<Formula> forms : formulae.values()) {
            for (Formula f : forms) {
                Node newNode = f.getNode().cloneNode(true);
                f.setNode(orderNode(newNode));
            }
        }
    }

    private Node orderNode(Node node) {
        if (node instanceof Element) {
        List<Node> nodes = new ArrayList<Node>();
        NodeList nl = node.getChildNodes();
        if (nl.getLength() > 1) {
            for (int i = 0; i < nl.getLength(); i++) {
                Node n = nl.item(i);
                orderNode(n);
                nodes.add(n);
            }
            if (mmlType == MathMLType.PRESENTATION) {
                    boolean switched;
                    for (Node justCycle : nodes) {
                        switched = false;
                        for (int i = 1; i < nodes.size() - 1; i++) {
                            Node n = nodes.get(i);
                            String name = n.getLocalName();
                            if (MathMLConstants.PMML_MO.equals(name)) {
                                String text = n.getTextContent();
                                if (ops.containsKey(text)) {
                                    Node n1 = nodes.get(i - 1);
                                    Node n2 = nodes.get(i + 1);
                                    boolean toSwap = toSwapNodes(n1, n2);
                                    if (toSwap && canSwap(text, i, nodes)) {
                                        nodes.set(i - 1, n2);
                                        nodes.set(i + 1, n1);
                                        switched = true;
                                    }
                                }
                            }
                        }
                        if (!switched) {
                            break;
                        }
                    }
                }
                if (mmlType == MathMLType.CONTENT) {
                    Node n = node.getFirstChild();
                    String name = n.getLocalName();
                    if (MathMLConstants.CMML_TIMES.equals(name) || MathMLConstants.CMML_PLUS.equals(name)) {
                        boolean swapped = true;
                        while (swapped) {
                            swapped = false;
                            for (int j = 1; j < nodes.size() - 1; j++) {
                                Node n1 = nodes.get(j);
                                Node n2 = nodes.get(j + 1);
                                if (toSwapNodes(n1, n2)) {
                                    nodes.set(j, n2);
                                    nodes.set(j + 1, n1);
                                    swapped = true;
                                }
                            }
                        }
                    }
                }
                for (Node n : nodes) {
                    node.appendChild(n);
                }
            }
        }
        return node;
    }

    private boolean toSwapNodes(Node n1, Node n2) {
        int c = n1.getNodeName().compareTo(n2.getNodeName());
        if (c == 0) {
            String n1Children = getNodeChildren(n1);
            String n2Children = getNodeChildren(n2);
            c = n1Children.compareTo(n2Children);
        }
        return c > 0 ? true : false;
    }

    private String getNodeChildren(Node node) {
        String result = "";
        result = nodeToString(node, true);
        return result;
    }
    
    /**
     * Converts a node to M-term styled string representation
     */
    private String nodeToString(Node node, boolean withoutTextContent) {
        StringBuilder builder = new StringBuilder();        
        Formula.nodeToString(builder, node, withoutTextContent, eldict, attrdict, MathMLConf.getIgnoreNode());
        return builder.toString();
    }

    /**
     * Determines if nodes around Node i in given list of Nodes can be swapped.
     *
     * @param i number of Node in the given list that sorrounding are to be swap
     * @param nodes List of childNodes of some formula, that are to be sorted
     * @return true if the Node i was operation + or * and the surrounding can be swapped,
     *         false otherwise
     */
    private boolean canSwap(String text, int i, List<Node> nodes) {
        boolean result = true;
        List<String> priorOps = ops.get(text);
        if (i - 2 >= 0) {
            Node n11 = nodes.get(i - 2);
            String n11text = n11.getTextContent();
            if (MathMLConstants.PMML_MO.equals(n11.getLocalName()) && priorOps.contains(n11text)) {
                result = false;
            }
        }
        if (i + 2 < nodes.size()) {
            Node n22 = nodes.get(i + 2);
            String n22text = n22.getTextContent();
            if (MathMLConstants.PMML_MO.equals(n22.getLocalName()) && priorOps.contains(n22text)) {
                result = false;
            }
        }
        return result;
    }

    /**
     * Provides all the modifying on the loaded formulae located in formuale map.
     * Calls several modifiing methods and specifies how they should alter the rank of
     * modified formula.
     */
    private void modify() {
        unifyVariables(vCoef);
        unifyConst(cCoef);
        processAttributes(aCoef);
    }
    
    /**
     * Unifies variables of each formula in formulae map
     *
     * @param rank Specifies the factor by which it should alter the rank of modified formula
     * 
     */
    private void unifyVariables(float rank) {
        List<Formula> result = new ArrayList<Formula>();
        for (List<Formula> forms : formulae.values()) {
            result.clear();
            for (Formula f : forms) {
                Node node = f.getNode();
                NodeList nl = node.getChildNodes();
                boolean hasElement = true;
                if (((nl.getLength() == 1) && !(nl.item(0) instanceof Element)) || nl.getLength() == 0) {
                    hasElement = false;
                }
                if (hasElement) {
                    Map<String, String> changes = new HashMap<String, String>();
                    Node newNode = node.cloneNode(true);
                    boolean changed = unifyVariablesNode(newNode, changes);
                    if (changed) {
                        result.add(new Formula(newNode, f.getWeight() * rank));
                    }
                }
            }
            forms.addAll(result);
        }
    }

    /**
     * Recursively modifying variables of the formula or subformula specified by given Node
     *
     * @param node Node representing current formula or subformula that is being modified
     * @param changes Map holding the performed changes, so that the variables with the same
     *        name are always substituted with the same unified name within the scope of each formula.
     * @return Saying whether or not this formula was modified
     */
    private boolean unifyVariablesNode(Node node, Map<String, String> changes) {
        boolean result = false;
        if (node instanceof Element) {
            NodeList nl = node.getChildNodes();
            for (int j = 0; j < nl.getLength(); j++) {
                result = unifyVariablesNode(nl.item(j), changes) == false ? result : true;
            }
            if (MathMLConstants.PMML_MI.equals(node.getLocalName()) || MathMLConstants.CMML_CI.equals(node.getLocalName())) {
                String oldVar = node.getTextContent();
                String newVar = toVar(oldVar, changes);
                node.setTextContent(newVar);
                return true;
            }
        }
        return result;
    }

    /**
     * Helping method performs substitution of the variable based on the given map of
     * already done changes.
     *
     * @param oldVar Variable to be unified
     * @param changes Map with already done changes.
     * @return new name of the variable
     */
    private String toVar(String oldVar, Map<String, String> changes) {
        String newVar = changes.get(oldVar);
        if (newVar == null) {
            newVar = "" + (changes.size() + 1);
            changes.put(oldVar, newVar);
        }
        return newVar;
    }

    /**
     * Performing unifying of all the constants in the formula by substituting them for "const" string.
     *
     * @param rank Specifies how the method should alter modified formulae
     */
    private void unifyConst(float rank) {
        List<Formula> result = new ArrayList<Formula>();
        for (List<Formula> forms : formulae.values()) {
            result.clear();
            for (Formula f : forms) {
                Node node = f.getNode();
                NodeList nl = node.getChildNodes();
                boolean hasElement = true;
                if (((nl.getLength() == 1) && !(nl.item(0) instanceof Element)) || nl.getLength() == 0) {
                    hasElement = false;
                }
                if (hasElement) {
                    Node newNode = node.cloneNode(true);
                    boolean changed = unifyConstNode(newNode);
                    if (changed) {
                        result.add(new Formula(newNode, f.getWeight() * rank));
                    }
                }
            }
            forms.addAll(result);
        }
    }

    /**
     * Recursively modifying constants of the formula or subformula specified by given Node
     *
     * @param node Node representing current formula or subformula that is being modified
     * @return Saying whether or not this formula was modified
     */
    private boolean unifyConstNode(Node node) {
        boolean result = false;
        if (node instanceof Element) {
            NodeList nl = node.getChildNodes();
            for (int j = 0; j < nl.getLength(); j++) {
                result = unifyConstNode(nl.item(j)) == false ? result : true;
            }
            if (MathMLConstants.PMML_MN.equals(node.getLocalName()) || MathMLConstants.CMML_CN.equals(node.getLocalName())) {
                node.setTextContent("\u00B6");
                return true;
            }
        }
        return result;
    }

    /**
     * @return Processed formulae to be used for a query. No subformulae are extracted.
     */
    public Map<String, Float> getQueryFormulae() {
        Map<String, Float> result = new HashMap<String, Float>();
        for (List<Formula> forms : formulae.values()) {
            for (Formula f : forms) {
                result.put(nodeToString(f.getNode(), false), f.getWeight());
            }
        }
        return result;
    }

    private void printMap(Map<Integer, List<Formula>> formulae) {
        for (Map.Entry<Integer, List<Formula>> entry : formulae.entrySet()) {
            List<Formula> forms = entry.getValue();
            for (Formula f : forms) {
                LOG.info(entry.getKey() + " " + nodeToString(f.getNode(), false) + " " + f.getWeight());
            }
            LOG.info("");
        }
    }

    /**
     * Prints numbers of processed formulae to standard output
     */
    public static void printFormulaeCount() {
        LOG.info("Input formulae: " + inputF.get());
        LOG.info("Indexed formulae: " + producedF.get());
    }

    /**
     * @return A map with formulae as if they are indexed. Key of the map is the original document position of the extracted formulae contained in the value of the map.
     */
    public Map<Integer, List<Formula>> getFormulae() {
        return formulae;
    }
    
}
