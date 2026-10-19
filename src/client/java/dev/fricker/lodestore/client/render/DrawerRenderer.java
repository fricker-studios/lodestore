package dev.fricker.lodestore.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import dev.fricker.lodestore.block.DrawerBlock;
import dev.fricker.lodestore.block.DrawerBlockEntity;

/**
 * Draws the stored item on the drawer's front face.
 *
 * <p>Shows whichever item the drawer holds most of. Without this a drawer gives the player no
 * feedback at all: a full drawer and an empty one look identical from the outside.
 */
public class DrawerRenderer implements BlockEntityRenderer<DrawerBlockEntity, DrawerRenderState> {
	/** Half a block, plus a sliver, to sit just proud of the face without z-fighting. */
	private static final float FACE_OFFSET = 0.5f + 0.01f;
	private static final float ICON_SCALE = 0.45f;
	/** The filter on an empty locked drawer is drawn smaller, so it reads as "not here yet". */
	private static final float GHOST_SCALE = 0.72f;
	private static final float TEXT_SCALE = 0.014f;
	/** The gold from the lock upgrade's own sprite, so a locked count is recognisably its doing. */
	private static final int LOCKED_COLOR = 0xFFF2C852;

	private final ItemModelResolver itemModelResolver;
	private final Font font;

	public DrawerRenderer(BlockEntityRendererProvider.Context context) {
		this.itemModelResolver = context.itemModelResolver();
		this.font = context.font();
	}

	/** Draws the stored count under the icon. */
	private void submitCount(DrawerRenderState state, PoseStack poseStack, SubmitNodeCollector collector) {
		String label = abbreviate(state.amount);

		poseStack.pushPose();
		poseStack.translate(0.0f, -0.24f, 0.001f);
		// Text is authored in screen pixels with +Y down, so shrink hard and flip Y.
		poseStack.scale(TEXT_SCALE, -TEXT_SCALE, TEXT_SCALE);

		collector.submitText(poseStack, -font.width(label) / 2.0f, 0.0f,
				Component.literal(label).getVisualOrderText(), true, Font.DisplayMode.POLYGON_OFFSET,
				state.lightCoords, state.locked ? LOCKED_COLOR : 0xFFFFFFFF, 0, 0);

		poseStack.popPose();
	}

	/** 8000 reads better than 8000 at a glance once drawers hold five figures. */
	private static String abbreviate(long amount) {
		if (amount < 1_000) {
			return Long.toString(amount);
		}

		if (amount < 1_000_000) {
			return (amount / 1_000) + "k";
		}

		return (amount / 1_000_000) + "M";
	}

	@Override
	public DrawerRenderState createRenderState() {
		return new DrawerRenderState();
	}

	@Override
	public void extractRenderState(DrawerBlockEntity drawer, DrawerRenderState state, float partialTick,
			Vec3 cameraPos, ModelFeatureRenderer.CrumblingOverlay crumbling) {
		BlockEntityRenderState.extractBase(drawer, state, crumbling);

		state.facing = drawer.getBlockState().getValue(DrawerBlock.FACING);

		// The preview is synced on its own, so the client never holds the inventory.
		ItemStack preview = drawer.previewItem();
		ItemStack locked = drawer.lockedItem();

		// A locked drawer goes on showing its filter after the last item leaves. Without this an
		// empty locked drawer is blank, which is exactly the drawer a player most needs labelled.
		state.locked = !locked.isEmpty();
		state.ghost = preview.isEmpty() && state.locked;

		ItemStack shown = state.ghost ? locked : preview;
		state.blank = shown.isEmpty();
		state.amount = drawer.previewCount();

		if (state.blank) {
			state.item.clear();
			return;
		}

		// extractBase does not fill lightCoords, and an unlit item renders as a solid
		// black silhouette. Sample the open block the icon sits in, as item frames do,
		// rather than the drawer itself, which is opaque and therefore dark.
		if (drawer.getLevel() != null) {
			state.lightCoords = LightCoordsUtil.getLightCoords(
					drawer.getLevel(), drawer.getBlockPos().relative(state.facing));
		}

		// FIXED is the in-world context. GUI assumes the inventory's own projection and
		// renders black out here.
		itemModelResolver.updateForTopItem(state.item, shown,
				ItemDisplayContext.FIXED, drawer.getLevel(), null, 0);
	}

	@Override
	public void submit(DrawerRenderState state, PoseStack poseStack, SubmitNodeCollector collector,
			CameraRenderState camera) {
		if (state.blank) {
			return;
		}

		poseStack.pushPose();

		// Rotating by -yRot makes local +Z point along the drawer's facing, so the icon
		// sits on the front face and looks outward without a second flip.
		poseStack.translate(0.5f, 0.5f, 0.5f);
		poseStack.mulPose(Axis.YP.rotationDegrees(-state.facing.toYRot()));
		poseStack.translate(0.0f, 0.0f, FACE_OFFSET);

		// Scale uniformly: flattening Z collapses the normals of block models and they
		// light as solid black.
		poseStack.pushPose();
		poseStack.translate(0.0f, 0.12f, 0.0f);
		float scale = state.ghost ? ICON_SCALE * GHOST_SCALE : ICON_SCALE;
		poseStack.scale(scale, scale, scale);
		state.item.submit(poseStack, collector, state.lightCoords, OverlayTexture.NO_OVERLAY, 0);
		poseStack.popPose();

		submitCount(state, poseStack, collector);

		poseStack.popPose();
	}
}
