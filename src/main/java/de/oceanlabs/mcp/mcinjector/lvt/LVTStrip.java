package de.oceanlabs.mcp.mcinjector.lvt;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import de.oceanlabs.mcp.mcinjector.MCInjector;

public class LVTStrip extends ClassVisitor
{
    ClassNode node;

    public LVTStrip(ClassVisitor cn, ClassNode node)
    {
        super(Opcodes.ASM9, cn);
        this.node = node;
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions)
    {
        return new MethodVisitor(api, cv.visitMethod(access, name, desc, signature, exceptions))
        {
            @Override
            public void visitEnd()
            {
                for (MethodNode mn : node.methods)
                {
                    if (mn.name.equals(name) && mn.desc.equals(desc) && mn.localVariables != null && mn.localVariables.size() > 0)
                    {
                        MCInjector.LOG.fine("    Stripping LVT: " + mn.localVariables.size());
                        mn.localVariables = null;
                        break;
                    }
                }
                super.visitEnd();
            }
        };
    }
}
