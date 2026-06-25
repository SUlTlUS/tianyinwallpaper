#!/usr/bin/env python3
"""Export the SHARP predictor to the Android ONNX interface.

This script is intended for the developer workstation, not for Android runtime.
It exports the predictor core with:

  inputs:
    image: [1, 3, input_size, input_size] float32
    disparity_factor: [1] float32

  outputs:
    mean_vectors: [1, N, 3]
    singular_values: [1, N, 3]
    quaternions: [1, N, 4]
    colors: [1, N, 3]
    opacities: [1, N]
"""

from __future__ import annotations

import argparse
from pathlib import Path

import torch

from sharp.models import PredictorParams, create_predictor


class SharpOnnxWrapper(torch.nn.Module):
    def __init__(
        self,
        checkpoint_path: Path,
        fp16: bool = False,
        grad_checkpointing: bool = False,
        use_patch_overlap: bool = True,
    ):
        super().__init__()
        state_dict = torch.load(checkpoint_path, map_location="cpu", weights_only=True)
        params = PredictorParams()
        params.monodepth.grad_checkpointing = grad_checkpointing
        params.monodepth.use_patch_overlap = use_patch_overlap
        params.gaussian_decoder.grad_checkpointing = grad_checkpointing
        predictor = create_predictor(params)
        predictor.load_state_dict(state_dict)
        predictor.eval()
        if fp16:
            predictor = predictor.half()
        self.predictor = predictor
        self.fp16 = fp16

    def forward(self, image: torch.Tensor, disparity_factor: torch.Tensor):
        if self.fp16:
            image = image.half()
            disparity_factor = disparity_factor.half()
        device_type = image.device.type if image.device.type != "cpu" else "cuda"
        with torch.autocast(device_type=device_type, dtype=torch.float16, enabled=self.fp16):
            gaussians = self.predictor(image, disparity_factor)
        return (
            gaussians.mean_vectors.float(),
            gaussians.singular_values.float(),
            gaussians.quaternions.float(),
            gaussians.colors.float(),
            gaussians.opacities.float(),
        )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--checkpoint", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--opset", type=int, default=17)
    parser.add_argument("--fp16", action="store_true")
    parser.add_argument("--grad-checkpointing", action="store_true")
    parser.add_argument("--no-patch-overlap", action="store_true")
    parser.add_argument("--device", default="cuda" if torch.cuda.is_available() else "cpu")
    parser.add_argument("--input-size", type=int, default=1536)
    args = parser.parse_args()

    model = SharpOnnxWrapper(
        args.checkpoint,
        fp16=args.fp16,
        grad_checkpointing=args.grad_checkpointing,
        use_patch_overlap=not args.no_patch_overlap,
    )
    device = torch.device(args.device)
    model = model.to(device)
    image = torch.zeros(1, 3, args.input_size, args.input_size, dtype=torch.float32, device=device)
    disparity_factor = torch.ones(1, dtype=torch.float32, device=device)

    args.output.parent.mkdir(parents=True, exist_ok=True)
    with torch.no_grad():
        torch.onnx.export(
            model,
            (image, disparity_factor),
            str(args.output),
            input_names=["image", "disparity_factor"],
            output_names=[
                "mean_vectors",
                "singular_values",
                "quaternions",
                "colors",
                "opacities",
            ],
            dynamic_axes={
                "mean_vectors": {0: "batch", 1: "num_gaussians"},
                "singular_values": {0: "batch", 1: "num_gaussians"},
                "quaternions": {0: "batch", 1: "num_gaussians"},
                "colors": {0: "batch", 1: "num_gaussians"},
                "opacities": {0: "batch", 1: "num_gaussians"},
            },
            opset_version=args.opset,
            do_constant_folding=True,
            dynamo=False,
            external_data=True,
        )


if __name__ == "__main__":
    main()
