#!/usr/bin/env python3
"""Export Apple SHARP into staged ONNX files for Android.

This is a developer-workstation script. It creates a split model package:

  manifest.json
  spn_patch_encoder_fp16.onnx
  spn_image_encoder_fp16.onnx
  spn_merge_upsample_fp16.onnx
  monodepth_decoder_head_fp16.onnx
  gaussian_predictor_head_fp16.onnx

The Android runtime can load these stages one at a time instead of keeping the
whole SHARP graph resident in memory.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

import torch

from sharp.models import PredictorParams, create_predictor
from sharp.models.encoders.spn_encoder import merge


PATCH_ENCODER = "spn_patch_encoder"
IMAGE_ENCODER = "spn_image_encoder"
MERGE_UPSAMPLE = "spn_merge_upsample"
MONODEPTH_DECODER_HEAD = "monodepth_decoder_head"
GAUSSIAN_PREDICTOR_HEAD = "gaussian_predictor_head"
FUSED_TAIL_GAUSSIAN = "spn_fused_tail_gaussian"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


class SpnPatchEncoderExport(torch.nn.Module):
    def __init__(self, predictor: torch.nn.Module, fp16: bool):
        super().__init__()
        encoder = predictor.monodepth_model.monodepth_predictor.encoder
        self.patch_encoder = encoder.patch_encoder
        ids = encoder.patch_intermediate_features_ids
        self.feature_id_0 = ids[0]
        self.feature_id_1 = ids[1]
        self.fp16 = fp16

    def forward(self, patches: torch.Tensor):
        if self.fp16:
            patches = patches.half()
        final, intermediates = self.patch_encoder(patches)
        return final, intermediates[self.feature_id_0], intermediates[self.feature_id_1]


class SpnImageEncoderExport(torch.nn.Module):
    def __init__(self, predictor: torch.nn.Module, fp16: bool):
        super().__init__()
        self.image_encoder = predictor.monodepth_model.monodepth_predictor.encoder.image_encoder
        self.fp16 = fp16

    def forward(self, x2_patch: torch.Tensor):
        if self.fp16:
            x2_patch = x2_patch.half()
        lowres, _ = self.image_encoder(x2_patch)
        return lowres


class SpnMergeUpsampleExport(torch.nn.Module):
    def __init__(self, predictor: torch.nn.Module, fp16: bool, x0_count: int = 25, x1_count: int = 9, padding: int = 3):
        super().__init__()
        encoder = predictor.monodepth_model.monodepth_predictor.encoder
        self.patch_encoder = encoder.patch_encoder
        self.upsample_latent0 = encoder.upsample_latent0
        self.upsample_latent1 = encoder.upsample_latent1
        self.upsample0 = encoder.upsample0
        self.upsample1 = encoder.upsample1
        self.upsample2 = encoder.upsample2
        self.upsample_lowres = encoder.upsample_lowres
        self.fuse_lowres = encoder.fuse_lowres
        self.fp16 = fp16
        self.x0_count = x0_count
        self.x1_count = x1_count
        self.padding = padding

    def forward(
        self,
        patch_final: torch.Tensor,
        patch_intermediate_0: torch.Tensor,
        patch_intermediate_1: torch.Tensor,
        image_lowres: torch.Tensor,
    ):
        if self.fp16:
            patch_final = patch_final.half()
            patch_intermediate_0 = patch_intermediate_0.half()
            patch_intermediate_1 = patch_intermediate_1.half()
            image_lowres = image_lowres.half()
        # Fixed SHARP default layout for 1536 input with overlap:
        # high-res 5x5 + mid-res 3x3 + low-res 1x1.
        x2_count = 1

        latent0 = self.patch_encoder.reshape_feature(patch_intermediate_0)
        latent0 = merge(latent0[: self.x0_count], batch_size=1, padding=self.padding)
        latent1 = self.patch_encoder.reshape_feature(patch_intermediate_1)
        latent1 = merge(latent1[: self.x0_count], batch_size=1, padding=self.padding)

        x0_enc, x1_enc, x2_enc = torch.split(patch_final, [self.x0_count, self.x1_count, x2_count], dim=0)
        x0 = merge(x0_enc, batch_size=1, padding=self.padding)
        x1 = merge(x1_enc, batch_size=1, padding=2 * self.padding)
        x2 = x2_enc

        latent0 = self.upsample_latent0(latent0)
        latent1 = self.upsample_latent1(latent1)
        x0 = self.upsample0(x0)
        x1 = self.upsample1(x1)
        x2 = self.upsample2(x2)
        lowres = self.upsample_lowres(image_lowres)
        lowres = self.fuse_lowres(torch.cat((x2, lowres), dim=1))
        return latent0, latent1, x0, x1, lowres


class MonodepthDecoderHeadExport(torch.nn.Module):
    def __init__(self, predictor: torch.nn.Module, fp16: bool):
        super().__init__()
        monodepth = predictor.monodepth_model.monodepth_predictor
        self.decoder = monodepth.decoder
        self.head = monodepth.head
        self.fp16 = fp16

    def forward(self, enc0, enc1, enc2, enc3, enc4):
        if self.fp16:
            enc0 = enc0.half()
            enc1 = enc1.half()
            enc2 = enc2.half()
            enc3 = enc3.half()
            enc4 = enc4.half()
        features = self.decoder([enc0, enc1, enc2, enc3, enc4])
        disparity = self.head(features)
        return disparity


class GaussianPredictorHeadExport(torch.nn.Module):
    def __init__(self, predictor: torch.nn.Module, fp16: bool):
        super().__init__()
        self.init_model = predictor.init_model
        self.feature_model = predictor.feature_model
        self.prediction_head = predictor.prediction_head
        self.gaussian_composer = predictor.gaussian_composer
        self.fp16 = fp16

    def forward(self, image, monodepth, enc0, enc1, enc2, enc3, enc4):
        if self.fp16:
            image = image.half()
            monodepth = monodepth.half()
            enc0 = enc0.half()
            enc1 = enc1.half()
            enc2 = enc2.half()
            enc3 = enc3.half()
            enc4 = enc4.half()
        init_output = self.init_model(image, monodepth)
        image_features = self.feature_model(
            init_output.feature_input,
            encodings=[enc0, enc1, enc2, enc3, enc4],
        )
        delta = self.prediction_head(image_features)
        gaussians = self.gaussian_composer(
            delta=delta,
            base_values=init_output.gaussian_base_values,
            global_scale=init_output.global_scale,
        )
        return (
            gaussians.mean_vectors.float(),
            gaussians.singular_values.float(),
            gaussians.quaternions.float(),
            gaussians.colors.float(),
            gaussians.opacities.float(),
        )


class FusedTailGaussianExport(torch.nn.Module):
    def __init__(self, predictor: torch.nn.Module, fp16: bool, x0_count: int = 25, x1_count: int = 9, padding: int = 3):
        super().__init__()
        self.merge = SpnMergeUpsampleExport(predictor, fp16, x0_count=x0_count, x1_count=x1_count, padding=padding)
        self.depth = MonodepthDecoderHeadExport(predictor, fp16)
        self.gaussian = GaussianPredictorHeadExport(predictor, fp16)
        self.fp16 = fp16

    def forward(
        self,
        patch_final,
        patch_intermediate_0,
        patch_intermediate_1,
        image_lowres,
        image,
        disparity_factor,
    ):
        enc0, enc1, enc2, enc3, enc4 = self.merge(
            patch_final,
            patch_intermediate_0,
            patch_intermediate_1,
            image_lowres,
        )
        disparity = self.depth(enc0, enc1, enc2, enc3, enc4)
        if self.fp16:
            disparity_factor = disparity_factor.half()
        factor = disparity_factor.reshape(1, 1, 1, 1)
        monodepth = factor / torch.clamp(disparity, min=1e-4, max=1e4)
        return self.gaussian(image, monodepth, enc0, enc1, enc2, enc3, enc4)


def export_onnx(
    module,
    args,
    output: Path,
    input_names: list[str],
    output_names: list[str],
    opset: int,
    dynamic_axes: dict | None = None,
) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    module.eval()
    with torch.no_grad():
        torch.onnx.export(
            module,
            args,
            str(output),
            input_names=input_names,
            output_names=output_names,
            dynamic_axes=dynamic_axes,
            opset_version=opset,
            do_constant_folding=True,
            dynamo=False,
            external_data=True,
        )


def add_manifest_entry(entries: list[dict], name: str, path: Path, inputs: list[str], outputs: list[str]) -> None:
    entries.append(
        {
            "name": name,
            "file": path.name,
            "sha256": sha256(path),
            "sizeBytes": path.stat().st_size,
            "inputs": inputs,
            "outputs": outputs,
        }
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--checkpoint", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--opset", type=int, default=17)
    parser.add_argument("--device", default="cuda" if torch.cuda.is_available() else "cpu")
    parser.add_argument("--fp16", action="store_true")
    parser.add_argument("--no-overlap", action="store_true", help="Use SHARP's 21-patch no-overlap SPN layout.")
    args = parser.parse_args()

    state_dict = torch.load(args.checkpoint, map_location="cpu", weights_only=True)
    params = PredictorParams()
    predictor = create_predictor(params)
    predictor.load_state_dict(state_dict)
    predictor.eval()
    if args.fp16:
        predictor = predictor.half()
    device = torch.device(args.device)
    predictor = predictor.to(device)
    dtype = torch.float32

    image = torch.zeros(1, 3, 1536, 1536, dtype=dtype, device=device)
    x2_patch = torch.zeros(1, 3, 384, 384, dtype=dtype, device=device)
    x0_count = 16 if args.no_overlap else 25
    x1_count = 4 if args.no_overlap else 9
    patch_padding = 0 if args.no_overlap else 3
    total_patches = x0_count + x1_count + 1
    patches = torch.zeros(total_patches, 3, 384, 384, dtype=dtype, device=device)

    out = args.output_dir
    entries: list[dict] = []

    patch_path = out / "spn_patch_encoder_fp16.onnx"
    export_onnx(
        SpnPatchEncoderExport(predictor, args.fp16),
        (patches,),
        patch_path,
        ["patches"],
        ["patch_final", "patch_intermediate_0", "patch_intermediate_1"],
        args.opset,
        dynamic_axes={
            "patches": {0: "num_patches"},
            "patch_final": {0: "num_patches"},
            "patch_intermediate_0": {0: "num_patches"},
            "patch_intermediate_1": {0: "num_patches"},
        },
    )
    add_manifest_entry(entries, PATCH_ENCODER, patch_path, ["patches"], ["patch_final", "patch_intermediate_0", "patch_intermediate_1"])

    image_path = out / "spn_image_encoder_fp16.onnx"
    export_onnx(SpnImageEncoderExport(predictor, args.fp16), (x2_patch,), image_path, ["x2_patch"], ["image_lowres"], args.opset)
    add_manifest_entry(entries, IMAGE_ENCODER, image_path, ["x2_patch"], ["image_lowres"])

    with torch.no_grad():
        patch_final, patch_i0, patch_i1 = SpnPatchEncoderExport(predictor, args.fp16)(patches)
        image_lowres = SpnImageEncoderExport(predictor, args.fp16)(x2_patch)

    merge_path = out / "spn_merge_upsample_fp16.onnx"
    merge_module = SpnMergeUpsampleExport(
        predictor,
        args.fp16,
        x0_count=x0_count,
        x1_count=x1_count,
        padding=patch_padding,
    )
    export_onnx(
        merge_module,
        (patch_final, patch_i0, patch_i1, image_lowres),
        merge_path,
        ["patch_final", "patch_intermediate_0", "patch_intermediate_1", "image_lowres"],
        ["enc0", "enc1", "enc2", "enc3", "enc4"],
        args.opset,
    )
    add_manifest_entry(entries, MERGE_UPSAMPLE, merge_path, ["patch_final", "patch_intermediate_0", "patch_intermediate_1", "image_lowres"], ["enc0", "enc1", "enc2", "enc3", "enc4"])

    with torch.no_grad():
        enc0, enc1, enc2, enc3, enc4 = merge_module(patch_final, patch_i0, patch_i1, image_lowres)

    depth_path = out / "monodepth_decoder_head_fp16.onnx"
    export_onnx(
        MonodepthDecoderHeadExport(predictor, args.fp16),
        (enc0, enc1, enc2, enc3, enc4),
        depth_path,
        ["enc0", "enc1", "enc2", "enc3", "enc4"],
        ["disparity"],
        args.opset,
    )
    add_manifest_entry(entries, MONODEPTH_DECODER_HEAD, depth_path, ["enc0", "enc1", "enc2", "enc3", "enc4"], ["disparity"])

    monodepth = torch.ones(1, 2, 1536, 1536, dtype=dtype, device=device)
    gaussian_path = out / "gaussian_predictor_head_fp16.onnx"
    export_onnx(
        GaussianPredictorHeadExport(predictor, args.fp16),
        (image, monodepth, enc0, enc1, enc2, enc3, enc4),
        gaussian_path,
        ["image", "monodepth", "enc0", "enc1", "enc2", "enc3", "enc4"],
        ["mean_vectors", "singular_values", "quaternions", "colors", "opacities"],
        args.opset,
    )
    add_manifest_entry(entries, GAUSSIAN_PREDICTOR_HEAD, gaussian_path, ["image", "monodepth", "enc0", "enc1", "enc2", "enc3", "enc4"], ["mean_vectors", "singular_values", "quaternions", "colors", "opacities"])

    fused_tail_path = out / "spn_fused_tail_gaussian_fp16.onnx"
    disparity_factor = torch.ones(1, dtype=dtype, device=device)
    export_onnx(
        FusedTailGaussianExport(
            predictor,
            args.fp16,
            x0_count=x0_count,
            x1_count=x1_count,
            padding=patch_padding,
        ),
        (patch_final, patch_i0, patch_i1, image_lowres, image, disparity_factor),
        fused_tail_path,
        ["patch_final", "patch_intermediate_0", "patch_intermediate_1", "image_lowres", "image", "disparity_factor"],
        ["mean_vectors", "singular_values", "quaternions", "colors", "opacities"],
        args.opset,
    )
    add_manifest_entry(
        entries,
        FUSED_TAIL_GAUSSIAN,
        fused_tail_path,
        ["patch_final", "patch_intermediate_0", "patch_intermediate_1", "image_lowres", "image", "disparity_factor"],
        ["mean_vectors", "singular_values", "quaternions", "colors", "opacities"],
    )

    manifest = {
        "version": 1,
        "precision": "fp16" if args.fp16 else "fp32",
        "inputSize": 1536,
        "patchSize": 384,
        "normalization": "affine_0_1_to_minus_1_1_before_spn",
        "patchLayout": {"x0": x0_count, "x1": x1_count, "x2": 1, "padding": patch_padding, "total": total_patches},
        "defaultPatchChunk": 1 if args.no_overlap else 4,
        "models": entries,
    }
    (out / "manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    print(f"Wrote split SHARP package to {out}")


if __name__ == "__main__":
    main()
