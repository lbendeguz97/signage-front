# PowerShell script to download Age and Gender TFLite models
# Place this script in the root of the signage-front project and execute it.

$assetsDir = Join-Path $PSScriptRoot "app/src/main/assets"
if (-not (Test-Path $assetsDir)) {
    Write-Host "Creating assets directory: $assetsDir"
    New-Item -ItemType Directory -Force -Path $assetsDir | Out-Null
}

$models = @(
    @{
        Url = "https://github.com/shubham0204/Age-Gender_Estimation_TF-Android/raw/master/app/src/main/assets/model_age_q.tflite"
        Dest = Join-Path $assetsDir "model_age.tflite"
        Name = "model_age.tflite"
    },
    @{
        Url = "https://github.com/shubham0204/Age-Gender_Estimation_TF-Android/raw/master/app/src/main/assets/model_gender_q.tflite"
        Dest = Join-Path $assetsDir "model_gender.tflite"
        Name = "model_gender.tflite"
    }
)

foreach ($model in $models) {
    Write-Host "Downloading $($model.Name) from $($model.Url)..."
    try {
        Invoke-WebRequest -Uri $model.Url -OutFile $model.Dest -ErrorAction Stop
        Write-Host "Successfully downloaded $($model.Name) to $($model.Dest)"
    } catch {
        Write-Error "Failed to download $($model.Name): $_"
    }
}

Write-Host "Download complete!"
