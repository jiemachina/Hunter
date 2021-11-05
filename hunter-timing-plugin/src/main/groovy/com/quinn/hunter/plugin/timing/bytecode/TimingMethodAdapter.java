package com.quinn.hunter.plugin.timing.bytecode;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.LocalVariablesSorter;

/**
 * // 第一组
 * (visitParameter)*
 * [visitAnnotationDefault]
 * (visitAnnotation | visitAnnotableParameterCount | visitParameterAnnotation | visitTypeAnnotation | visitAttribute)*
 * // 第二组
 * [
 *     visitCode // 标志着方法开始
 *     (
 *         visitFrame |
 *         visitXxxInsn |
 *         visitLabel |
 *         visitInsnAnnotation |
 *         visitTryCatchBlock |
 *         visitTryCatchAnnotation |
 *         visitLocalVariable |
 *         visitLocalVariableAnnotation |
 *         visitLineNumber
 *     )*
 *     visitMaxs // 标志着方法结束
 * ]
 * // 第三组
 * visitEnd
 */
public final class TimingMethodAdapter extends LocalVariablesSorter implements Opcodes {

    private int startVarIndex;

    private String methodName;

    public TimingMethodAdapter(String name, int access, String desc, MethodVisitor mv) {
        super(Opcodes.ASM7, access, desc, mv);
        this.methodName = name.replace("/", ".");
    }

    /**
     * 方法体开始
     */
    @Override
    public void visitCode() {
        super.visitCode();
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "currentTimeMillis", "()J", false);
        startVarIndex = newLocal(Type.LONG_TYPE);
        mv.visitVarInsn(Opcodes.LSTORE, startVarIndex);
    }

    /**
     * 方法体的构建
     * @param opcode
     */
    @Override
    public void visitInsn(int opcode) {
        if ((opcode >= IRETURN && opcode <= RETURN) || opcode == ATHROW) {
            // 返回之前执行的指令代码
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/System", "currentTimeMillis", "()J", false);
            mv.visitVarInsn(LLOAD, startVarIndex);
            mv.visitInsn(LSUB);
            int index = newLocal(Type.LONG_TYPE);
            mv.visitVarInsn(LSTORE, index);
            mv.visitLdcInsn(methodName);
            mv.visitVarInsn(LLOAD, index);
            mv.visitMethodInsn(INVOKESTATIC, "com/hunter/library/timing/BlockManager", "timingMethod", "(Ljava/lang/String;J)V", false);
        }
        super.visitInsn(opcode);
    }

}
