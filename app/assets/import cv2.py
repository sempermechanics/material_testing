import cv2
import numpy as np

# 1. The exact TIFF file DICe is using
tiff_path = r"C:\Users\Guillermo\Desktop\Rafad- 2D - DIC Smartphone App\References\Test Subjects from DIC Challenge\2D-Challenge 1.0-20260203T091813Z-3-001\2D-Challenge 1.0\Sample12\oht_cfrp_00.tiff"

# 2. The exact PNG file you put in your Android app
png_path = r"C:\Users\Guillermo\Desktop\Rafad- 2D - DIC Smartphone App\IndicVisionDIC\app\src\main\assets\oht_cfrp_00.png"

def print_top_left(image_path, label):
    img = cv2.imread(image_path, cv2.IMREAD_GRAYSCALE)
    if img is None:
        print(f"❌ Failed to load {label}: {image_path}")
        return None
    
    print(f"\n--- {label} RAW INTENSITY DUMP (Top-Left 10x10) ---")
    for y in range(10):
        row_str = f"Row {y}: " + " ".join([f"{val}.000000" for val in img[y, 0:10]])
        print(row_str)
    return img

print("🔍 RUNNING FORENSIC FILE CHECK...")

tiff_img = print_top_left(tiff_path, "ORIGINAL TIFF (DICe)")
png_img = print_top_left(png_path, "ANDROID PNG ASSET")

if tiff_img is not None and png_img is not None:
    if np.array_equal(tiff_img, png_img):
        print("\n✅ MATCH: The PNG is a perfect mathematical copy of the TIFF.")
    else:
        print("\n❌ MISMATCH: The PNG and TIFF have different pixel values on your PC!")