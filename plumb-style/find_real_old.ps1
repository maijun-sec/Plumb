$files = Get-Content "D:\work\workspace\idea\engine\Plumb\plumb-style\real_old_style.txt" -ErrorAction SilentlyContinue
foreach ($f in $files) {
  $path = "src\main\java\org\plumb\style\style\" + $f
  if (Test-Path $path) {
    $content = Get-Content $path -Raw -ErrorAction SilentlyContinue
    if ($content -match 'visit\(CompilationUnit n, StyleContext ctx\) \{') {
      Write-Host $f
    }
  }
}