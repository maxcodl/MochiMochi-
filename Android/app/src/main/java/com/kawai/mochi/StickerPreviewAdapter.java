package com.kawai.mochi;

import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.facebook.drawee.backends.pipeline.Fresco;
import com.facebook.drawee.controller.BaseControllerListener;
import com.facebook.drawee.interfaces.DraweeController;
import com.facebook.drawee.view.SimpleDraweeView;
import com.facebook.imagepipeline.common.ResizeOptions;
import com.facebook.imagepipeline.image.ImageInfo;
import com.facebook.imagepipeline.request.ImageRequest;
import com.facebook.imagepipeline.request.ImageRequestBuilder;
import com.facebook.shimmer.ShimmerFrameLayout;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class StickerPreviewAdapter extends RecyclerView.Adapter<StickerPreviewAdapter.ViewHolder> {
    public interface StickerInteractionListener {
        void onStickerHoldStarted(@NonNull Sticker sticker, @NonNull Uri stickerUri, boolean animatedPack);

        void onStickerHoldEnded();
    }

    private static final ExecutorService decodeExecutor = Executors.newFixedThreadPool(
            Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors())));
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final StickerBitmapLruCache bitmapCache = StickerBitmapLruCache.getInstance();

    private List<Sticker> stickers;
    private String packIdentifier;
    private int previewSize;
    private int marginBetween;
    private boolean isAnimatedPack;
    private boolean animationsEnabled;
    private boolean isScrolling;
    /** True when used in a GridLayoutManager (details view); false for the horizontal list strip. */
    private final boolean isGridMode;
    @Nullable
    private final StickerInteractionListener interactionListener;

    public StickerPreviewAdapter(List<Sticker> stickers, String packIdentifier, int previewSize,
                                 int marginBetween, boolean isAnimatedPack, boolean animationsEnabled,
                                 boolean isGridMode, @Nullable StickerInteractionListener interactionListener) {
        this.stickers = stickers;
        this.packIdentifier = packIdentifier;
        this.previewSize = previewSize;
        this.marginBetween = marginBetween;
        this.isAnimatedPack = isAnimatedPack;
        this.animationsEnabled = animationsEnabled;
        this.isGridMode = isGridMode;
        this.interactionListener = interactionListener;
        setHasStableIds(true);
    }

    public StickerPreviewAdapter(List<Sticker> stickers, String packIdentifier, int previewSize,
                                 int marginBetween, boolean isAnimatedPack, boolean animationsEnabled,
                                 boolean isGridMode) {
        this(stickers, packIdentifier, previewSize, marginBetween, isAnimatedPack, animationsEnabled, isGridMode, null);
    }

    /** Convenience constructor for horizontal list strip mode (isGridMode = false). */
    public StickerPreviewAdapter(List<Sticker> stickers, String packIdentifier, int previewSize, int marginBetween, boolean isAnimatedPack, boolean animationsEnabled) {
        this(stickers, packIdentifier, previewSize, marginBetween, isAnimatedPack, animationsEnabled, false, null);
    }

    public void setScrolling(boolean isScrolling) {
        this.isScrolling = isScrolling;
    }

    /**
     * Update the adapter's data in-place.
     * If the pack identifier is unchanged, images are still in Fresco's memory cache and no
     * rebind is needed. Only triggers a full rebind when the actual pack changes.
     */
    public void updateData(List<Sticker> newStickers, String newPackId,
                           int newPreviewSize, int newMargin,
                           boolean newIsAnimated, boolean newAnimEnabled, boolean newIsScrolling) {
        boolean packChanged = !newPackId.equals(packIdentifier)
                || newIsAnimated != isAnimatedPack
                || newAnimEnabled != animationsEnabled
                || newIsScrolling != isScrolling;
        this.stickers = newStickers;
        this.packIdentifier = newPackId;
        this.previewSize = newPreviewSize;
        this.marginBetween = newMargin;
        this.isAnimatedPack = newIsAnimated;
        this.animationsEnabled = newAnimEnabled;
        this.isScrolling = newIsScrolling;
        if (packChanged) {
            notifyDataSetChanged();
        }
    }

    @Override
    public long getItemId(int position) {
        return stickers.get(position).imageFileName.hashCode();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.sticker_packs_list_image_item, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Sticker sticker = stickers.get(position);

        holder.bindToken++;
        long token = holder.bindToken;
        cancelDecode(holder);
        holder.errorView.setVisibility(View.GONE);
        holder.bitmapView.setImageDrawable(null);
        holder.bitmapView.setVisibility(View.GONE);
        holder.draweeView.setController(null);
        holder.draweeView.setVisibility(View.GONE);
        holder.skeletonView.setVisibility(View.VISIBLE);
        holder.skeletonView.startShimmer();

        // Use original file for animated stickers or grid mode; static in list mode uses thumbnail
        String fileName = (isGridMode) ? sticker.imageFileName : (sticker.isAnimated ? sticker.imageFileName : "thumbs/thumb_" + sticker.imageFileName);
        final Uri fileUri = StickerPackLoader.getStickerAssetUri(packIdentifier, fileName);

        // Set render resolution: 96px for list strip (Retina-sharp for small previews), full previewSize for grid
        int decodeSize = isGridMode ? previewSize : 96;

        String cacheKey = packIdentifier + "/" + fileName + "@" + decodeSize;
        holder.boundCacheKey = cacheKey;

        holder.itemView.setOnLongClickListener(v -> {
            if (interactionListener == null) {
                return false;
            }
            interactionListener.onStickerHoldStarted(sticker, fileUri, sticker.isAnimated);
            return true;
        });
        holder.itemView.setOnTouchListener((v, event) -> {
            if (interactionListener == null) {
                return false;
            }
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                interactionListener.onStickerHoldEnded();
            }
            return false;
        });

        if (sticker.isAnimated) {
            bindAnimatedSticker(holder, fileUri, decodeSize, token);
        } else {
            bindStaticSticker(holder, fileUri, decodeSize, cacheKey, token);
        }

        applyLayout(holder, position);
    }

    private void bindAnimatedSticker(@NonNull ViewHolder holder, @NonNull Uri fileUri,
                                     int decodeSize, long token) {
        // Show draweeView immediately so it can show placeholder/image as it loads
        holder.draweeView.setVisibility(View.VISIBLE);
        
        ImageRequestBuilder requestBuilder = ImageRequestBuilder.newBuilderWithSource(fileUri);
        // ResizeOptions can sometimes cause issues with animated WebP in some Fresco versions
        // when downsampling is enabled. We only use it for very large previews in grid mode.
        if (isGridMode && decodeSize > 128) {
            requestBuilder.setResizeOptions(new ResizeOptions(decodeSize, decodeSize));
        }
        ImageRequest request = requestBuilder.build();

        DraweeController controller = Fresco.newDraweeControllerBuilder()
                .setImageRequest(request)
                .setAutoPlayAnimations(animationsEnabled && !isScrolling)
                .setControllerListener(new BaseControllerListener<ImageInfo>() {
                    @Override
                    public void onFinalImageSet(String id, @Nullable ImageInfo imageInfo, @Nullable android.graphics.drawable.Animatable animatable) {
                        if (holder.bindToken != token) {
                            return;
                        }
                        holder.skeletonView.stopShimmer();
                        holder.skeletonView.setVisibility(View.GONE);
                    }

                    @Override
                    public void onFailure(String id, Throwable throwable) {
                        android.util.Log.e("StickerPreviewAdapter", "Animated sticker failed to load: " + fileUri, throwable);
                        if (holder.bindToken != token) {
                            return;
                        }
                        // Fallback: If animated path fails, try static decode for first frame
                        holder.draweeView.setVisibility(View.GONE);
                        bindStaticSticker(holder, fileUri, decodeSize, holder.boundCacheKey, token);
                    }
                })
                .setOldController(holder.draweeView.getController())
                .build();

        holder.draweeView.setController(controller);
    }

    private void bindStaticSticker(@NonNull ViewHolder holder, @NonNull Uri fileUri,
                                   int decodeSize, @NonNull String cacheKey, long token) {
        Bitmap cached = bitmapCache.get(cacheKey);
        if (cached != null && !cached.isRecycled()) {
            holder.bitmapView.setImageBitmap(cached);
            holder.bitmapView.setVisibility(View.VISIBLE);
            holder.skeletonView.stopShimmer();
            holder.skeletonView.setVisibility(View.GONE);
            return;
        }

        holder.decodeFuture = decodeExecutor.submit(() -> {
            Bitmap decoded;
            try {
                decoded = StickerStaticDecoder.decode(holder.itemView.getContext().getApplicationContext(), fileUri, decodeSize, decodeSize);
                if (decoded != null) {
                    bitmapCache.put(cacheKey, decoded);
                }
            } catch (Throwable t) {
                decoded = null;
            }

            final Bitmap result = decoded;
            mainHandler.post(() -> {
                if (holder.bindToken != token || !cacheKey.equals(holder.boundCacheKey)) {
                    return;
                }
                if (result == null) {
                    showDecodeError(holder);
                    return;
                }
                holder.bitmapView.setImageBitmap(result);
                holder.bitmapView.setVisibility(View.VISIBLE);
                holder.skeletonView.stopShimmer();
                holder.skeletonView.setVisibility(View.GONE);
            });
        });
    }

    private void applyLayout(@NonNull ViewHolder holder, int position) {
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) holder.itemView.getLayoutParams();
        if (lp == null) {
            lp = new ViewGroup.MarginLayoutParams(previewSize, previewSize);
        }
        lp.width = previewSize;
        lp.height = previewSize;
        if (isGridMode) {
            // In grid mode apply a uniform half-margin on every side so inter-item gaps are
            // consistent both horizontally and vertically.
            int half = marginBetween / 2;
            lp.topMargin = half;
            lp.bottomMargin = half;
            lp.setMarginStart(half);
            lp.setMarginEnd(half);
        } else {
            // Horizontal list strip: only separate items from each other with a start margin.
            lp.topMargin = 0;
            lp.bottomMargin = 0;
            lp.setMarginEnd(0);
            lp.setMarginStart(position > 0 ? marginBetween : 0);
        }
        holder.itemView.setLayoutParams(lp);
    }

    private void showDecodeError(@NonNull ViewHolder holder) {
        holder.bitmapView.setImageDrawable(null);
        holder.draweeView.setController(null);
        holder.bitmapView.setVisibility(View.GONE);
        holder.draweeView.setVisibility(View.GONE);
        holder.errorView.setVisibility(View.VISIBLE);
        holder.skeletonView.stopShimmer();
        holder.skeletonView.setVisibility(View.GONE);
    }

    private void cancelDecode(@NonNull ViewHolder holder) {
        if (holder.decodeFuture != null) {
            holder.decodeFuture.cancel(true);
            holder.decodeFuture = null;
        }
    }

    @Override
    public void onViewRecycled(@NonNull ViewHolder holder) {
        super.onViewRecycled(holder);
        cancelDecode(holder);
        holder.bindToken++;
        holder.boundCacheKey = "";
        holder.bitmapView.setImageDrawable(null);
        holder.draweeView.setController(null);
        holder.skeletonView.stopShimmer();
        holder.skeletonView.setVisibility(View.GONE);
        holder.errorView.setVisibility(View.GONE);
    }

    @Override
    public int getItemCount() {
        return stickers == null ? 0 : stickers.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final ShimmerFrameLayout skeletonView;
        final ImageView bitmapView;
        final SimpleDraweeView draweeView;
        final ImageView errorView;
        @Nullable
        Future<?> decodeFuture;
        long bindToken;
        @NonNull
        String boundCacheKey = "";

        ViewHolder(View itemView) {
            super(itemView);
            this.skeletonView = itemView.findViewById(R.id.sticker_skeleton);
            this.bitmapView = itemView.findViewById(R.id.sticker_bitmap_preview);
            this.draweeView = itemView.findViewById(R.id.sticker_pack_list_item_image);
            this.errorView = itemView.findViewById(R.id.sticker_preview_error);
            // Disable Fresco fade for snappier list/grid updates
            this.draweeView.getHierarchy().setFadeDuration(0);
        }
    }
}
