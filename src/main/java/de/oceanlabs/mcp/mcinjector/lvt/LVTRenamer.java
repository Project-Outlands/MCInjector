package de.oceanlabs.mcp.mcinjector.lvt;

import java.util.Collections;
import java.util.Comparator;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodNode;

import de.oceanlabs.mcp.mcinjector.MCInjector;

public abstract class LVTRenamer extends ClassVisitor
{
    private static final boolean DEBUG = Boolean.parseBoolean(System.getProperty("mci.printLVTRenames", "false"));
    private static final char SNOWMAN = 0x2603; // MCP marks meaningless LVT names with a snowman
    String className;
    ClassNode node;

    public LVTRenamer(ClassVisitor cn, ClassNode node)
    {
        super(Opcodes.ASM9, cn);
        this.node = node;
    }

    @Override
    public void visit(int version, int access, String name, String signature, String superName, String[] interfaces)
    {
        this.className = name;
        super.visit(version, access, name, signature, superName, interfaces);
    }

    @Override
    public void visitEnd() {
        super.visitEnd();

        for (final MethodNode mn : node.methods)
        {
            if (mn.localVariables != null && mn.localVariables.size() > 0)
            {
                Collections.sort(mn.localVariables, new Comparator<LocalVariableNode>()
                {
                    @Override
                    public int compare(LocalVariableNode o1, LocalVariableNode o2)
                    {
                        if (o1.index < o2.index) return -1;
                        if (o1.index > o2.index) return 1;
                        int o1Start = mn.instructions.indexOf(o1.start);
                        int o2Start = mn.instructions.indexOf(o2.start);
                        if (o1Start < o2Start) return -1;
                        if (o1Start > o2Start) return 1;
                        return 0;
                    }

                });

                for (LocalVariableNode lvn : mn.localVariables)
                {
                    if (lvn.name.isEmpty() || SNOWMAN != lvn.name.charAt(0)) // Only rename MCP's snowman markers
                        continue;
                    String name = getNewName(mn, lvn);
                    if (DEBUG)
                        MCInjector.LOG.info("    Renaming LVT: " + lvn.index + " " + lvn.name + " " + lvn.desc + " -> " + name);
                    lvn.name = name;
                }
            }
        }
    }

    protected abstract String getNewName(MethodNode mtd, LocalVariableNode lvn);
}
