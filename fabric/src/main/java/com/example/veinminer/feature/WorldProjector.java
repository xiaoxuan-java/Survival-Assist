package com.example.veinminer.feature;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector4f;

/**
 * 将世界坐标投影到屏幕坐标（用于 HUD 阶段渲染世界内标签）。
 * 采用标准的 视图矩阵 * 投影矩阵 * 世界坐标 列向量变换顺序。
 */
public class WorldProjector {

    public static class ScreenPos {
        public final double x;
        public final double y;
        public final boolean visible;

        public ScreenPos(double x, double y, boolean visible) {
            this.x = x;
            this.y = y;
            this.visible = visible;
        }
    }

    public static ScreenPos project(Vec3d worldPos, MinecraftClient client) {
        Camera camera = client.gameRenderer.getCamera();
        Vec3d camPos = camera.getPos();

        float fov = client.options.getFov().getValue().floatValue();
        Matrix4f proj = client.gameRenderer.getBasicProjectionMatrix(fov);

        // 视图矩阵 = 相机旋转的逆旋转（共轭四元数）
        Quaternionf invRot = camera.getRotation().conjugate(new Quaternionf());

        // viewProj = proj * R_inv * T_inv（右乘顺序）
        Matrix4f viewProj = new Matrix4f(proj);
        viewProj.rotate(invRot);
        viewProj.translate((float) -camPos.x, (float) -camPos.y, (float) -camPos.z);

        // 列向量变换：clip = viewProj * world
        Vector4f v = new Vector4f(
                (float) worldPos.x,
                (float) worldPos.y,
                (float) worldPos.z,
                1.0f
        );
        viewProj.transform(v);

        if (v.w <= 0.01f) {
            return new ScreenPos(0, 0, false);
        }

        float ndcX = v.x / v.w;
        float ndcY = v.y / v.w;

        if (ndcX < -1.0f || ndcX > 1.0f || ndcY < -1.0f || ndcY > 1.0f) {
            return new ScreenPos(0, 0, false);
        }

        int sw = client.getWindow().getScaledWidth();
        int sh = client.getWindow().getScaledHeight();
        // NDC -> 屏幕坐标（屏幕 Y 轴向下）
        double sx = (ndcX * 0.5 + 0.5) * sw;
        double sy = (1.0 - (ndcY * 0.5 + 0.5)) * sh;
        return new ScreenPos(sx, sy, true);
    }
}
