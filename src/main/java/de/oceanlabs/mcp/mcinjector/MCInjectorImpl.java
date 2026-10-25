package de.oceanlabs.mcp.mcinjector;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.logging.Level;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import de.oceanlabs.mcp.mcinjector.adaptors.AccessFixer;
import de.oceanlabs.mcp.mcinjector.adaptors.ApplyMap;
import de.oceanlabs.mcp.mcinjector.adaptors.ClassInitAdder;
import de.oceanlabs.mcp.mcinjector.adaptors.InnerClassInitAdder;
import de.oceanlabs.mcp.mcinjector.adaptors.ParameterAnnotationFixer;
import de.oceanlabs.mcp.mcinjector.data.Access;
import de.oceanlabs.mcp.mcinjector.data.Constructors;
import de.oceanlabs.mcp.mcinjector.data.Exceptions;
import de.oceanlabs.mcp.mcinjector.lvt.LVTFernflower;
import de.oceanlabs.mcp.mcinjector.lvt.LVTLvt;
import de.oceanlabs.mcp.mcinjector.lvt.LVTNaming;
import de.oceanlabs.mcp.mcinjector.lvt.LVTStrip;

public class MCInjectorImpl
{
    private static final OutputStream NULL_STREAM = new OutputStream()
    {
        @Override public void write(int b) {}
        @Override public void write(byte[] b, int off, int len) {}
    };

    public LVTNaming naming = LVTNaming.STRIP;
    private Map<String, List<String>> abstractParameters = new HashMap<>();

    static void process(
            Path in, Path out,
            Path accIn, Path accOut,
            Path ctrIn, Path ctrOut,
            Path excIn, Path excOut,
            LVTNaming naming)
        throws IOException
    {
        if (accIn != null)
            Access.INSTANCE.load(accIn);
        if (ctrIn != null)
            Constructors.INSTANCE.load(ctrIn);
        if (excIn != null)
            Exceptions.INSTANCE.load(excIn);

        MCInjector.LOG.info("Processing: " + in);
        MCInjector.LOG.info("  Output: " + out);

        MCInjectorImpl mci = new MCInjectorImpl();
        mci.naming = naming;

        mci.processJar(in, out);

        if (accOut != null)
            Access.INSTANCE.dump(accOut);
        if (ctrOut != null)
            Constructors.INSTANCE.dump(ctrOut);
        if (excOut != null)
            Exceptions.INSTANCE.dump(excOut);

        MCInjector.LOG.info("Processed " + in);
    }

    private MCInjectorImpl(){}

    private void processJar(Path inFile, Path outFile) throws IOException
    {
        Set<String> entries = new HashSet<>();
        byte[] data = new byte[4096];
        try (ZipInputStream inJar = new ZipInputStream(new BufferedInputStream(Files.newInputStream(inFile))))
        {
            try (ZipOutputStream outJar = new ZipOutputStream(outFile == null ? NULL_STREAM : new BufferedOutputStream(Files.newOutputStream(outFile))))
            {
                for (ZipEntry entry = inJar.getNextEntry(); entry != null; entry = inJar.getNextEntry())
                {
                    String entryName = entry.getName();

                    if (entry.isDirectory())
                    {
                        JarEntry dir = new JarEntry(entryName);
                        dir.setTime(0); //Stabilize time.
                        outJar.putNextEntry(dir);
                        outJar.closeEntry();
                        continue;
                    }

                    ByteArrayOutputStream entryBuffer = new ByteArrayOutputStream();

                    int len;
                    while ((len = inJar.read(data)) != -1)
                    {
                        entryBuffer.write(data, 0, len);
                    }

                    byte[] entryData = entryBuffer.toByteArray();

                    boolean mojang = entryName.startsWith("net/minecraft/") || entryName.startsWith("com/mojang/");

                    if (entryName.endsWith(".class") && mojang) //TODO: Remove this hardcoding? SRG input? process all?
                    {
                        MCInjector.LOG.log(Level.INFO, "Processing " + entryName);

                        entryData = this.processClass(entryData, outFile == null);

                        MCInjector.LOG.log(Level.INFO, "Processed " + entryBuffer.size() + " -> " + entryData.length);
                    }
                    else
                    {
                        MCInjector.LOG.log(Level.INFO, "Copying " + entryName);
                    }

                    ZipEntry newEntry = new ZipEntry(entryName);
                    newEntry.setTime(0); //Stabilize time.
                    outJar.putNextEntry(newEntry);
                    outJar.write(entryData);
                    outJar.closeEntry();
                    entries.add(entryName);
                }

                if (!abstractParameters.isEmpty() && !entries.contains("fernflower_abstract_parameter_names.txt"))
                {
                    ZipEntry newEntry = new ZipEntry("fernflower_abstract_parameter_names.txt");
                    newEntry.setTime(0); //Stabilize time.
                    outJar.putNextEntry(newEntry);
                    for (String key : abstractParameters.keySet().stream().sorted().collect(Collectors.toList()))
                    {
                        outJar.write(key.getBytes(StandardCharsets.UTF_8));//class method desc
                        outJar.write(' ');
                        outJar.write(abstractParameters.get(key).stream().collect(Collectors.joining(" ")).getBytes(StandardCharsets.UTF_8));
                        outJar.write('\n');
                    }
                    outJar.closeEntry();
                }
            }
        }
    }

    public byte[] processClass(byte[] cls, boolean readOnly)
    {
        ClassReader cr = new ClassReader(cls);
        ClassNode cn = new ClassNode();

        ClassVisitor ca = cn;
        ca = new ApplyMap(this, ca, cn);

        switch (naming)
        {
            case STRIP:      ca = new LVTStrip     (ca, cn); break;
            case FERNFLOWER: ca = new LVTFernflower(ca, cn); break;
            case LVT:        ca = new LVTLvt       (ca, cn); break;
        }

        ca = new AccessFixer(ca);

        ca = new ParameterAnnotationFixer(ca, cn);
        ca = new InnerClassInitAdder(ca);
        ca = new ClassInitAdder(ca);

        ca = new ClassVisitor(Opcodes.ASM9, ca) //Top level, so we can print the logs.
        {
            @Override
            public void visit(int version, int access, String name, String signature, String superName, String[] interfaces)
            {
                if (MCInjector.LOG.isLoggable(Level.FINE))
                    MCInjector.LOG.log(Level.FINE, "Class: " + name + " Extends: " + superName);
                super.visit(version, access, name, signature, superName, interfaces);
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions)
            {
                if (MCInjector.LOG.isLoggable(Level.FINE))
                    MCInjector.LOG.log(Level.FINE, "  Name: " + name + " Desc: " + desc + " Sig: " + signature);
                return super.visitMethod(access, name, desc, signature, exceptions);
            }
        };

        cr.accept(ca, 0);

        if (readOnly)
            return cls;

        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(writer);
        return writer.toByteArray();
    }

    public void storeAbstractParameters(String className, String name, String desc, List<String> params)
    {
        abstractParameters.put(className + ' ' + name  + ' ' + desc, params);
    }
}
