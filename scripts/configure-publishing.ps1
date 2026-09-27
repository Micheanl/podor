$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
[Windows.Forms.Application]::EnableVisualStyles()
$form = [Windows.Forms.Form]::new()
$form.Text = 'podor 发布配置'
$form.ClientSize = [Drawing.Size]::new(540, 210)
$form.StartPosition = 'CenterScreen'
$form.FormBorderStyle = 'FixedDialog'
$form.MaximizeBox = $false
$form.MinimizeBox = $false
$label = [Windows.Forms.Label]::new()
$label.Text = "在下方粘贴 Gitee 的新令牌（projects 权限）。`n仅保存到本机 GITEE_TOKEN，不发送到聊天。"
$label.Location = [Drawing.Point]::new(24, 20)
$label.Size = [Drawing.Size]::new(490, 46)
$inputBox = [Windows.Forms.TextBox]::new()
$inputBox.Location = [Drawing.Point]::new(24, 80)
$inputBox.Size = [Drawing.Size]::new(490, 28)
$inputBox.UseSystemPasswordChar = $true
$save = [Windows.Forms.Button]::new()
$save.Text = '保存'
$save.Location = [Drawing.Point]::new(410, 138)
$save.Size = [Drawing.Size]::new(104, 36)
$save.Add_Click({
    if ([string]::IsNullOrWhiteSpace($inputBox.Text)) {
        [Windows.Forms.MessageBox]::Show('请先粘贴令牌。', 'podor') > $null
        return
    }
    try {
        [Environment]::SetEnvironmentVariable('GITEE_TOKEN', $inputBox.Text.Trim(), 'User')
        $inputBox.Clear()
        [Windows.Forms.MessageBox]::Show('已保存。回到 Codex 后将继续发布。', 'podor') > $null
        $form.Close()
    } catch {
        [Windows.Forms.MessageBox]::Show('保存失败，请检查当前用户权限。', 'podor') > $null
    }
})
$form.Controls.AddRange(@($label, $inputBox, $save))
$form.AcceptButton = $save
$form.Add_Shown({ $inputBox.Focus() })
try { $form.ShowDialog() > $null } finally { $form.Dispose() }
