package org.benf.cfr.reader.relationship;

import org.benf.cfr.reader.bytecode.analysis.types.JavaTypeInstance;
import org.benf.cfr.reader.bytecode.analysis.variables.Keywords;
import org.benf.cfr.reader.entities.ClassFile;
import org.benf.cfr.reader.entities.ClassFileField;
import org.benf.cfr.reader.entities.annotations.AnnotationTableEntry;
import org.benf.cfr.reader.entities.annotations.ElementValue;
import org.benf.cfr.reader.entities.annotations.ElementValueConst;
import org.benf.cfr.reader.state.DCCommonState;
import org.benf.cfr.reader.util.CannotLoadClassException;
import org.benf.cfr.reader.util.collections.SetFactory;
import org.benf.cfr.reader.util.output.IllegalIdentifierReplacement;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/*
 * Recover original field names that an obfuscator left behind in serialization annotations.
 *
 * ProGuard renames a field to (say) 'aDW' but leaves @SerializedName(value="account_id") intact, so the
 * original name survives on the annotation.  We rename the field to that value.  Like the enum rename this
 * runs as a first pass (before the dump loop) so referring classes - which resolve the same shared
 * ClassFileField - print the recovered name too.  Gated behind --renameannotationidents.
 */
public class AnnotationMemberResolver {
    // annotation fqn -> element holding the name.
    private static final String GSON_SERIALIZED_NAME = "com.google.gson.annotations.SerializedName";
    private static final String JAXB_XML_ELEMENT_JAVAX = "javax.xml.bind.annotation.XmlElement";
    private static final String JAXB_XML_ELEMENT_JAKARTA = "jakarta.xml.bind.annotation.XmlElement";
    private static final String JAXB_XML_ATTRIBUTE_JAVAX = "javax.xml.bind.annotation.XmlAttribute";
    private static final String JAXB_XML_ATTRIBUTE_JAKARTA = "jakarta.xml.bind.annotation.XmlAttribute";
    // JAXB's sentinel meaning "use the field name" - not a real name.
    private static final String JAXB_DEFAULT = "##default";

    public static void resolveNames(DCCommonState dcCommonState, Collection<? extends JavaTypeInstance> types) {
        for (JavaTypeInstance type : types) {
            try {
                resolveClass(dcCommonState.getClassFile(type));
            } catch (CannotLoadClassException ignore) {
                // Skip - nothing to rename if we can't load it.
            } catch (RuntimeException ignore) {
                // One bad class must not abort the run.
            }
        }
    }

    private static void resolveClass(ClassFile classFile) {
        List<ClassFileField> fields = classFile.getFields();
        Set<String> usedNames = SetFactory.newSet();
        for (ClassFileField field : fields) {
            usedNames.add(field.getFieldName());
        }

        for (ClassFileField field : fields) {
            String current = field.getFieldName();
            String recovered = recoveredName(field);
            if (recovered == null || recovered.equals(current)) {
                continue;
            }
            // isIllegal deliberately whitelists 'this'/'new', which are still invalid as field names.
            if (IllegalIdentifierReplacement.isIllegal(recovered) || Keywords.isAKeyword(recovered)) {
                continue;
            }
            if (usedNames.contains(recovered)) {
                classFile.addComment("Tried to rename field '" + current + "' to '" + recovered + "' but it's already used.");
                continue;
            }
            usedNames.remove(current);
            usedNames.add(recovered);
            field.overrideName(recovered);
        }
    }

    private static String recoveredName(ClassFileField field) {
        List<AnnotationTableEntry> annotations = field.getField().getAnnotationEntries();
        if (annotations == null) {
            return null;
        }
        for (AnnotationTableEntry annotation : annotations) {
            String fqn = annotation.getClazz().getRawName();
            String key;
            if (GSON_SERIALIZED_NAME.equals(fqn)) {
                key = "value";
            } else if (JAXB_XML_ELEMENT_JAVAX.equals(fqn) || JAXB_XML_ELEMENT_JAKARTA.equals(fqn)
                    || JAXB_XML_ATTRIBUTE_JAVAX.equals(fqn) || JAXB_XML_ATTRIBUTE_JAKARTA.equals(fqn)) {
                key = "name";
            } else {
                continue;
            }
            ElementValue value = annotation.getElementValue(key);
            if (!(value instanceof ElementValueConst)) {
                continue;
            }
            String name = ((ElementValueConst) value).getStringValue();
            if (name != null && !name.isEmpty() && !JAXB_DEFAULT.equals(name)) {
                return name;
            }
        }
        return null;
    }
}
