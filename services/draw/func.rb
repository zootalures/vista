require 'json'
require 'rubygems'
require 'open-uri'
require 'mini_magick'

require 'slack-ruby-client'

Slack.configure do |config|
  config.token = ENV['SLACK_API_TOKEN']
  fail 'Missing ENV[SLACK_API_TOKEN]!' unless config.token
end

def download_image(payload_in)
  payload = payload_in

  temp_image_name = "temp_image_#{payload["id"]}.jpg"

  File.open(temp_image_name, "wb") do |fout|
    open(payload["image_url"]) do |fin|
      IO.copy_stream(fin, fout)
    end
  end

  temp_image_name
end



std_in = STDIN.read
payload = JSON.parse(std_in)

temp_image_name = download_image(payload)

img = MiniMagick::Image.new(temp_image_name)

payload["rectangles"].each do |coords|
  img.combine_options do |c|
    draw_string = "rectangle #{coords["startx"]}, #{coords["starty"]}, #{coords["endx"]}, #{coords["endy"]}"
    c.fill('none')
    is_nude = payload["is_nude"] || "false"
    c.stroke('purple')
    c.strokewidth(10)
    c.draw draw_string
  end 
end

image_name = "image_#{payload["id"]}.jpg"
img.resize "300x300"
img.write(image_name)


Slack.configure do |config|
  config.token = ENV['SLACK_API_TOKEN']
end
client = Slack::Web::Client.new
client.auth_test

client.files_upload(
  channels: '#general',
  as_user: true,
  file: Faraday::UploadIO.new(image_name, 'image/jpeg'),
  title: "Found Plate:",
  filename: 'plate.jpg',
  initial_comment: 'Have you seen this car?'
)