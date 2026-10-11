Eraser models in app/src/main/assets/eraser, converted to TensorFlow Lite (litert-torch); outputs checked against the ONNX originals.

- migan.tflite: MI-GAN 512 Places2 (Picsart AI Research, https://huggingface.co/andraniksargsyan/migan), generator only, int8 weights, MIT License (LICENSE-migan)
- text.tflite: PP-OCRv4 text detector (PaddleOCR, ch_PP-OCRv4_det_infer.onnx from https://huggingface.co/SWHL/RapidOCR), float32, Apache License 2.0 (LICENSE-ppocr)
