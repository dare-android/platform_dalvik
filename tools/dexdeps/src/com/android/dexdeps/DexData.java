/*
 * Copyright (C) 2009 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.dexdeps;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Arrays;

/**
 * Data extracted from a DEX file.
 */
public class DexData {
    private RandomAccessFile mDexFile;
    private HeaderItem mHeaderItem;
    private String[] mStrings;              // strings from string_data_*
    private TypeIdItem[] mTypeIds;
    private ProtoIdItem[] mProtoIds;
    private FieldIdItem[] mFieldIds;
    private MethodIdItem[] mMethodIds;
    private ClassDefItem[] mClassDefs;

    private byte tmpBuf[] = new byte[4];
    private boolean isBigEndian = false;

    /**
     * Constructs a new DexData for this file.
     */
    public DexData(RandomAccessFile raf) {
        mDexFile = raf;
    }

    /**
     * Loads the contents of the DEX file into our data structures.
     *
     * @throws IOException if we encounter a problem while reading
     * @throws DexDataException if the DEX contents look bad
     */
    public void load() throws IOException {
        parseHeaderItem();

        loadStrings();
        loadTypeIds();
        loadProtoIds();
        loadFieldIds();
        loadMethodIds();
        loadClassDefs();

        markInternalClasses();
    }


    /**
     * Parses the interesting bits out of the header.
     */
    void parseHeaderItem() throws IOException {
        mHeaderItem = new HeaderItem();

        seek(0);

        byte[] magic = new byte[8];
        readBytes(magic);
        if (!Arrays.equals(magic, HeaderItem.DEX_FILE_MAGIC)) {
            System.err.println("Magic number is wrong -- are you sure " +
                "this is a DEX file?");
            throw new DexDataException();
        }

        /*
         * Read the endian tag, so we properly swap things as we read
         * them from here on.
         */
        seek(8+4+20+4+4);
        mHeaderItem.endianTag = readInt();
        if (mHeaderItem.endianTag == HeaderItem.ENDIAN_CONSTANT) {
            /* do nothing */
        } else if (mHeaderItem.endianTag == HeaderItem.REVERSE_ENDIAN_CONSTANT){
            /* file is big-endian (!), reverse future reads */
            isBigEndian = true;
        } else {
            System.err.println("Endian constant has unexpected value " +
                Integer.toHexString(mHeaderItem.endianTag));
            throw new DexDataException();
        }

        seek(8+4+20);  // magic, checksum, signature
        mHeaderItem.fileSize = readInt();
        mHeaderItem.headerSize = readInt();
        /*mHeaderItem.endianTag =*/ readInt();
        /*mHeaderItem.linkSize =*/ readInt();
        /*mHeaderItem.linkOff =*/ readInt();
        /*mHeaderItem.mapOff =*/ readInt();
        mHeaderItem.stringIdsSize = readInt();
        mHeaderItem.stringIdsOff = readInt();
        mHeaderItem.typeIdsSize = readInt();
        mHeaderItem.typeIdsOff = readInt();
        mHeaderItem.protoIdsSize = readInt();
        mHeaderItem.protoIdsOff = readInt();
        mHeaderItem.fieldIdsSize = readInt();
        mHeaderItem.fieldIdsOff = readInt();
        mHeaderItem.methodIdsSize = readInt();
        mHeaderItem.methodIdsOff = readInt();
        mHeaderItem.classDefsSize = readInt();
        mHeaderItem.classDefsOff = readInt();
        /*mHeaderItem.dataSize =*/ readInt();
        /*mHeaderItem.dataOff =*/ readInt();
    }

    /**
     * Loads the string table out of the DEX.
     *
     * First we read all of the string_id_items, then we read all of the
     * string_data_item.  Doing it this way should allow us to avoid
     * seeking around in the file.
     */
    void loadStrings() throws IOException {
        int count = mHeaderItem.stringIdsSize;
        int stringOffsets[] = new int[count];

        //System.out.println("reading " + count + " strings");

        seek(mHeaderItem.stringIdsOff);
        for (int i = 0; i < count; i++) {
            stringOffsets[i] = readInt();
        }

        mStrings = new String[count];

        seek(stringOffsets[0]);
        for (int i = 0; i < count; i++) {
            seek(stringOffsets[i]);         // should be a no-op
            mStrings[i] = readString();
            //System.out.println("STR: " + i + ": " + mStrings[i]);
        }
    }

    /**
     * Loads the type ID list.
     */
    void loadTypeIds() throws IOException {
        int count = mHeaderItem.typeIdsSize;
        mTypeIds = new TypeIdItem[count];

        //System.out.println("reading " + count + " typeIds");
        seek(mHeaderItem.typeIdsOff);
        for (int i = 0; i < count; i++) {
            mTypeIds[i] = new TypeIdItem();
            mTypeIds[i].descriptorIdx = readInt();

            //System.out.println(i + ": " + mTypeIds[i].descriptorIdx +
            //    " " + mStrings[mTypeIds[i].descriptorIdx]);
        }
    }

    /**
     * Loads the proto ID list.
     */
    void loadProtoIds() throws IOException {
        int count = mHeaderItem.protoIdsSize;
        mProtoIds = new ProtoIdItem[count];

        //System.out.println("reading " + count + " protoIds");
        seek(mHeaderItem.protoIdsOff);

        /*
         * Read the proto ID items.
         */
        for (int i = 0; i < count; i++) {
            mProtoIds[i] = new ProtoIdItem();
            mProtoIds[i].shortyIdx = readInt();
            mProtoIds[i].returnTypeIdx = readInt();
            mProtoIds[i].parametersOff = readInt();

            //System.out.println(i + ": " + mProtoIds[i].shortyIdx +
            //    " " + mStrings[mProtoIds[i].shortyIdx]);
        }

        /*
         * Go back through and read the type lists.
         */
        for (int i = 0; i < count; i++) {
            ProtoIdItem protoId = mProtoIds[i];

            int offset = protoId.parametersOff;

            if (offset == 0) {
                protoId.types = new int[0];
                continue;
            } else {
                seek(offset);
                int size = readInt();       // #of entries in list
                protoId.types = new int[size];

                for (int j = 0; j < size; j++) {
                    protoId.types[j] = readShort() & 0xffff;
                }
            }
        }
    }

    /**
     * Loads the field ID list.
     */
    void loadFieldIds() throws IOException {
        int count = mHeaderItem.fieldIdsSize;
        mFieldIds = new FieldIdItem[count];

        //System.out.println("reading " + count + " fieldIds");
        seek(mHeaderItem.fieldIdsOff);
        for (int i = 0; i < count; i++) {
            mFieldIds[i] = new FieldIdItem();
            mFieldIds[i].classIdx = readShort() & 0xffff;
            mFieldIds[i].typeIdx = readShort() & 0xffff;
            mFieldIds[i].nameIdx = readInt();

            //System.out.println(i + ": " + mFieldIds[i].nameIdx +
            //    " " + mStrings[mFieldIds[i].nameIdx]);
        }
    }

    /**
     * Loads the method ID list.
     */
    void loadMethodIds() throws IOException {
        int count = mHeaderItem.methodIdsSize;
        mMethodIds = new MethodIdItem[count];

        //System.out.println("reading " + count + " methodIds");
        seek(mHeaderItem.methodIdsOff);
        for (int i = 0; i < count; i++) {
            mMethodIds[i] = new MethodIdItem();
            mMethodIds[i].classIdx = readShort() & 0xffff;
            mMethodIds[i].protoIdx = readShort() & 0xffff;
            mMethodIds[i].nameIdx = readInt();

            //System.out.println(i + ": " + mMethodIds[i].nameIdx +
            //    " " + mStrings[mMethodIds[i].nameIdx]);
        }
    }

    /**
     * Loads the class defs list.
     */
    void loadClassDefs() throws IOException {
        int count = mHeaderItem.classDefsSize;
        mClassDefs = new ClassDefItem[count];

        //System.out.println("reading " + count + " classDefs");
        seek(mHeaderItem.classDefsOff);
        for (int i = 0; i < count; i++) {
            mClassDefs[i] = new ClassDefItem();
            mClassDefs[i].classIdx = readInt();

            /* access_flags = */ readInt();
            /* superclass_idx = */ readInt();
            /* interfaces_off = */ readInt();
            /* source_file_idx = */ readInt();
            /* annotations_off = */ readInt();
            /* class_data_off = */ readInt();
            /* static_values_off = */ readInt();

            //System.out.println(i + ": " + mClassDefs[i].classIdx + " " +
            //    mStrings[mTypeIds[mClassDefs[i].classIdx].descriptorIdx]);
        }
    }

    /**
     * Sets the "internal" flag on type IDs which are defined in the
     * DEX file or within the VM (e.g. primitive classes and arrays).
     */
    void markInternalClasses() {
        for (int i = mClassDefs.length -1; i >= 0; i--) {
            mTypeIds[mClassDefs[i].classIdx].internal = true;
        }

        for (int i = 0; i < mTypeIds.length; i++) {
            String className = mStrings[mTypeIds[i].descriptorIdx];

            if (className.length() == 1) {
                // primitive class
                mTypeIds[i].internal = true;
            } else if (className.charAt(0) == '[') {
                mTypeIds[i].internal = true;
            }

            //System.out.println(i + " " +
            //    (mTypeIds[i].internal ? "INTERNAL" : "external") + " - " +
            //    mStrings[mTypeIds[i].descriptorIdx]);
        }
    }


    /*
     * =======================================================================
     *      Queries
     * =======================================================================
     */

    /**
     * Converts a single-character primitive type into its human-readable
     * equivalent.
     */
    private String primitiveTypeLabel(char typeChar) {
        /* primitive type; substitute human-readable name in */
        switch (typeChar) {
            case 'B':   return "byte";
            case 'C':   return "char";
            case 'D':   return "double";
            case 'F':   return "float";
            case 'I':   return "int";
            case 'J':   return "long";
            case 'S':   return "short";
            case 'V':   return "void";
            case 'Z':   return "boolean";
            default:
                /* huh? */
                System.err.println("Unexpected class char " + typeChar);
                assert false;
                return "UNKNOWN";
        }
    }

    /**
     * Converts a descriptor to dotted form.  For example,
     * "Ljava/lang/String;" becomes "java.lang.String", and "[I" becomes
     * "int[].
     */
    private String descriptorToDot(String descr) {
        int targetLen = descr.length();
        int offset = 0;
        int arrayDepth = 0;

        /* strip leading [s; will be added to end */
        while (targetLen > 1 && descr.charAt(offset) == '[') {
            offset++;
            targetLen--;
        }
        arrayDepth = offset;

        if (targetLen == 1) {
            descr = primitiveTypeLabel(descr.charAt(offset));
            offset = 0;
            targetLen = descr.length();
        } else {
            /* account for leading 'L' and trailing ';' */
            if (targetLen >= 2 && descr.charAt(offset) == 'L' &&
                descr.charAt(offset+targetLen-1) == ';')
            {
                targetLen -= 2;     /* two fewer chars to copy */
                offset++;           /* skip the 'L' */
            }
        }

        char[] buf = new char[targetLen + arrayDepth * 2];

        /* copy class name over */
        int i;
        for (i = 0; i < targetLen; i++) {
            char ch = descr.charAt(offset + i);
            buf[i] = (ch == '/') ? '.' : ch;
        }

        /* add the appopriate number of brackets for arrays */
        while (arrayDepth-- > 0) {
            buf[i++] = '[';
            buf[i++] = ']';
        }
        assert i == buf.length;

        return new String(buf);
    }

    /**
     * Returns the dot-form class name, given an index into the type_ids
     * table.
     */
    private String classNameFromTypeIndex(int idx) {
        String descriptor = mStrings[mTypeIds[idx].descriptorIdx];
        return descriptorToDot(descriptor);
    }

    /**
     * Returns the method prototype descriptor, given an index into the
     * proto_ids table.
     */
    private String protoStringFromProtoIndex(int idx) {
        StringBuilder builder = new StringBuilder();
        ProtoIdItem protoId = mProtoIds[idx];

        builder.append("(");
        for (int i = 0; i < protoId.types.length; i++) {
            String elem = mStrings[mTypeIds[protoId.types[i]].descriptorIdx];
            builder.append(elem);
        }

        builder.append(")");
        String ret = mStrings[mTypeIds[protoId.returnTypeIdx].descriptorIdx];
        builder.append(ret);

        return builder.toString();
    }

    /**
     * Returns an array with all of the field references that don't
     * correspond to classes in the DEX file.
     */
    public FieldRef[] getExternalFieldReferences() {
        // get a count
        int count = 0;
        for (int i = 0; i < mFieldIds.length; i++) {
            if (!mTypeIds[mFieldIds[i].classIdx].internal)
                count++;
        }

        //System.out.println("count is " + count + " of " + mFieldIds.length);

        FieldRef[] fieldRefs = new FieldRef[count];
        count = 0;
        for (int i = 0; i < mFieldIds.length; i++) {
            if (!mTypeIds[mFieldIds[i].classIdx].internal) {
                FieldIdItem fieldId = mFieldIds[i];
                fieldRefs[count++] =
                    new FieldRef(classNameFromTypeIndex(fieldId.classIdx),
                                 classNameFromTypeIndex(fieldId.typeIdx),
                                 mStrings[fieldId.nameIdx]);
            }
        }

        assert count == fieldRefs.length;

        return fieldRefs;
    }

    /**
     * Returns an array with all of the method references that don't
     * correspond to classes in the DEX file.
     */
    public MethodRef[] getExternalMethodReferences() {
        // get a count
        int count = 0;
        for (int i = 0; i < mMethodIds.length; i++) {
            if (!mTypeIds[mMethodIds[i].classIdx].internal)
                count++;
        }

        //System.out.println("count is " + count + " of " + mMethodIds.length);

        MethodRef[] methodRefs = new MethodRef[count];
        count = 0;
        for (int i = 0; i < mMethodIds.length; i++) {
            if (!mTypeIds[mMethodIds[i].classIdx].internal) {
                MethodIdItem methodId = mMethodIds[i];
                methodRefs[count++] =
                    new MethodRef(classNameFromTypeIndex(methodId.classIdx),
                                 protoStringFromProtoIndex(methodId.protoIdx),
                                 mStrings[methodId.nameIdx]);
            }
        }

        assert count == methodRefs.length;

        return methodRefs;
    }

    /*
     * =======================================================================
     *      Basic I/O functions
     * =======================================================================
     */

    /**
     * Seeks the DEX file to the specified absolute position.
     */
    void seek(int position) throws IOException {
        mDexFile.seek(position);
    }

    /**
     * Fills the buffer by reading bytes from the DEX file.
     */
    void readBytes(byte[] buffer) throws IOException {
        mDexFile.readFully(buffer);
    }

    /**
     * Reads a single signed byte value.
     */
    byte readByte() throws IOException {
        mDexFile.readFully(tmpBuf, 0, 1);
        return tmpBuf[0];
    }

    /**
     * Reads a signed 16-bit integer, byte-swapping if necessary.
     */
    short readShort() throws IOException {
        mDexFile.readFully(tmpBuf, 0, 2);
        if (isBigEndian) {
            return (short) ((tmpBuf[1] & 0xff) | ((tmpBuf[0] & 0xff) << 8));
        } else {
            return (short) ((tmpBuf[0] & 0xff) | ((tmpBuf[1] & 0xff) << 8));
        }
    }

    /**
     * Reads a signed 32-bit integer, byte-swapping if necessary.
     */
    int readInt() throws IOException {
        mDexFile.readFully(tmpBuf, 0, 4);

        if (isBigEndian) {
            return (tmpBuf[3] & 0xff) | ((tmpBuf[2] & 0xff) << 8) |
                   ((tmpBuf[1] & 0xff) << 16) | ((tmpBuf[0] & 0xff) << 24);
        } else {
            return (tmpBuf[0] & 0xff) | ((tmpBuf[1] & 0xff) << 8) |
                   ((tmpBuf[2] & 0xff) << 16) | ((tmpBuf[3] & 0xff) << 24);
        }
    }

    /**
     * Reads a variable-length unsigned LEB128 value.  Does not attempt to
     * verify that the value is valid.
     *
     * @throws EOFException if we run off the end of the file
     */
    int readUnsignedLeb128() throws IOException {
        int result = 0;
        byte val;

        do {
            val = readByte();
            result = (result << 7) | (val & 0x7f);
        } while (val < 0);

        return result;
    }

    /**
     * Reads a UTF-8 string.
     *
     * We don't know how long the UTF-8 string is, so we have to read one
     * byte at a time.  We could make an educated guess based on the
     * utf16_size and seek back if we get it wrong, but seeking backward
     * may cause the underlying implementation to reload I/O buffers.
     */
    String readString() throws IOException {
        int utf16len = readUnsignedLeb128();
        byte inBuf[] = new byte[utf16len * 3];      // worst case
        int idx;

        for (idx = 0; idx < inBuf.length; idx++) {
            byte val = readByte();
            if (val == 0)
                break;
            inBuf[idx] = val;
        }

        return new String(inBuf, 0, idx, "UTF-8");
    }


    /*
     * =======================================================================
     *      Internal "structure" declarations
     * =======================================================================
     */

    /**
     * Holds the contents of a header_item.
     */
    static class HeaderItem {
        public int fileSize;
        public int headerSize;
        public int endianTag;
        public int stringIdsSize, stringIdsOff;
        public int typeIdsSize, typeIdsOff;
        public int protoIdsSize, protoIdsOff;
        public int fieldIdsSize, fieldIdsOff;
        public int methodIdsSize, methodIdsOff;
        public int classDefsSize, classDefsOff;

        /* expected magic values */
        public static final byte[] DEX_FILE_MAGIC = {
            0x64, 0x65, 0x78, 0x0a, 0x30, 0x33, 0x35, 0x00 };
        public static final int ENDIAN_CONSTANT = 0x12345678;
        public static final int REVERSE_ENDIAN_CONSTANT = 0x78563412;
    }

    /**
     * Holds the contents of a type_id_item.
     *
     * This is chiefly a list of indices into the string table.  We need
     * some additional bits of data, such as whether or not the type ID
     * represents a class defined in this DEX, so we use an object for
     * each instead of a simple integer.  (Could use a parallel array, but
     * since this is a desktop app it's not essential.)
     */
    static class TypeIdItem {
        public int descriptorIdx;       // index into string_ids

        public boolean internal;        // defined within this DEX file?
    }

    /**
     * Holds the contents of a proto_id_item.
     */
    static class ProtoIdItem {
        public int shortyIdx;           // index into string_ids
        public int returnTypeIdx;       // index into type_ids
        public int parametersOff;       // file offset to a type_list

        public int types[];             // contents of type list
    }

    /**
     * Holds the contents of a field_id_item.
     */
    static class FieldIdItem {
        public int classIdx;            // index into type_ids (defining class)
        public int typeIdx;             // index into type_ids (field type)
        public int nameIdx;             // index into string_ids
    }

    /**
     * Holds the contents of a method_id_item.
     */
    static class MethodIdItem {
        public int classIdx;            // index into type_ids
        public int protoIdx;            // index into proto_ids
        public int nameIdx;             // index into string_ids
    }

    /**
     * Holds the contents of a class_def_item.
     *
     * We don't really need a class for this, but there's some stuff in
     * the class_def_item that we might want later.
     */
    static class ClassDefItem {
        public int classIdx;            // index into type_ids
    }
}
