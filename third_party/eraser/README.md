Eraser models in app/src/main/assets/eraser, converted to TensorFlow Lite (litert-torch); outputs checked against the ONNX originals.

- migan.tflite: MI-GAN 512 Places2 (Picsart AI Research, https://huggingface.co/andraniksargsyan/migan), generator only, int8 weights, MIT License (LICENSE-migan)
- text.tflite: PP-OCRv4 text detector (PaddleOCR, ch_PP-OCRv4_det_infer.onnx from https://huggingface.co/SWHL/RapidOCR), float32, Apache License 2.0 (LICENSE-ppocr)
- ocr/latin_rec.tflite (app/src/main/assets/ocr): PP-OCRv5 Latin text recognizer (PaddleOCR, latin_PP-OCRv5_mobile_rec, ONNX from https://huggingface.co/monkt/paddleocr-onnx), 32 languages, input 48 x 960, float32, Apache License 2.0 (LICENSE-ppocr); ocr/latin_dict.txt is its character list.
