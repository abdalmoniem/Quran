'''
Author:        AbdAlMoniem AlHifnawy
Date:          24 Sep. 2023
Description:   Downloads Quran chapters' images as svgs and converts them to png
'''

import os
import time
import requests
import cairosvg

# base url where svg images are hosted
baseUrl = 'https://salattimes.com/wp-content/uploads/2020/10/'

# create directories for svgs and pngs
# TODO: reference drawable directory in res directly
if not os.path.isdir('svgs'): os.mkdir('svgs')
if not os.path.isdir('pngs'): os.mkdir('pngs')

# Quran has 114 chapters
for chapterIndex in range (1, 115):
   # append chapter number to base url
   url = baseUrl + f'{chapterIndex:03d}.svg'

   # download svg image
   print(f'downloading {url}')
   request = requests.get(url, allow_redirects=True)

   # save svg image to disk
   print(f'saving to svgs/chapter_{chapterIndex:03d}.svg ...')
   open(f'svgs/chapter_{chapterIndex:03d}.svg', 'wb').write(request.content)

   # convert svg to png image and save it to disk
   print(f'converting to PNG in pngs/chapter_{chapterIndex:03d}.png ...', end='\n\n')
   cairosvg.svg2png(url=f'svgs/{chapterIndex:03d}.svg', write_to=f'pngs/chapter_{chapterIndex:03d}.png')

   # delay of 100ms between request to avoid rate limiting
   time.sleep(0.1)